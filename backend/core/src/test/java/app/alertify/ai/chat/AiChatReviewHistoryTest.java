package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.json.JsonMapper;

class AiChatReviewHistoryTest {
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @Test
    void preservesChronologicalResultsAcrossTurnsAndDistinguishesEmptyFromUnavailable() {
        var create = invocation(1, 7, "DOMAIN", "{}");
        create.succeed("{\"id\":202}");
        create.approvalDecision("APPROVED", "USER", "tester", "subject-1");
        var internal = invocation(2, 8, "INTERNAL", "{}");
        internal.succeed("loaded");
        var snapshot = JSON.readTree(AiChatReviewHistory.encode(JSON, List.of(internal, create)));
        assertThat(snapshot.path("available").booleanValue()).isTrue();
        assertThat(snapshot.path("truncated").booleanValue()).isFalse();
        assertThat(snapshot.path("entries").get(0).path("turnId").longValue()).isEqualTo(7);
        assertThat(snapshot.path("entries").get(0).path("result").stringValue()).isEqualTo("{\"id\":202}");
        assertThat(snapshot.path("entries").get(0).path("approvalDecision").stringValue()).isEqualTo("APPROVED");
        assertThat(snapshot.path("entries").get(0).path("approvalActor").stringValue()).isEqualTo("tester");
        assertThat(snapshot.path("entries").get(0).path("approvalDecidedAt").isNull()).isFalse();
        assertThat(snapshot.path("entries").get(1).path("kind").stringValue()).isEqualTo("INTERNAL");
        assertThat(snapshot.path("entries").get(1).path("invocationId").longValue()).isEqualTo(2);
        var empty = JSON.readTree(AiChatReviewHistory.encode(JSON, List.of()));
        assertThat(empty.path("available").booleanValue()).isTrue();
        assertThat(empty.path("entries").size()).isZero();
        assertThat(JSON.readTree(AiChatReviewHistory.UNAVAILABLE).path("available").booleanValue()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "SUCCEEDED", "FAILED", "REJECTED", "CANCELLED", "INTERRUPTED", "RUNNING", "PENDING_APPROVAL" })
    void retainsActualStatusWithoutFabricatingResults(String status) {
        var invocation = invocation(1, 7, "DOMAIN", "{}");
        ReflectionTestUtils.setField(invocation, "status", status);
        var entry = JSON.readTree(AiChatReviewHistory.encode(JSON, List.of(invocation))).path("entries").get(0);
        assertThat(entry.path("status").stringValue()).isEqualTo(status);
        assertThat(entry.path("result").isNull()).isTrue();
    }

    @Test
    void keepsTheLatestFortyEntriesAndReportsOmittedHistory() {
        List<AiToolInvocation> entries = new ArrayList<>();
        for (int id = 41; id > 0; id--)
            entries.add(invocation(id, 7, "DOMAIN", "{}"));

        var snapshot = JSON.readTree(AiChatReviewHistory.encode(JSON, entries));
        assertThat(snapshot.path("truncated").booleanValue()).isTrue();
        assertThat(snapshot.path("entries").size()).isEqualTo(40);
        assertThat(snapshot.path("entries").get(0).path("invocationId").longValue()).isEqualTo(2);
        assertThat(snapshot.path("entries").get(39).path("invocationId").longValue()).isEqualTo(41);
    }

    @Test
    void truncatesUtf8FieldsWithoutSplittingCodePointsAndKeepsJsonValid() {
        String content = "a" + "😀".repeat(20000);
        var invocation = invocation(1, 7, "DOMAIN", content);
        invocation.succeed(content);
        ReflectionTestUtils.setField(invocation, "reviewReason", content);
        var snapshot = JSON.readTree(AiChatReviewHistory.encode(JSON, List.of(invocation)));
        assertThat(snapshot.path("truncated").booleanValue()).isTrue();
        var entry = snapshot.path("entries").get(0);
        assertThat(entry.path("truncatedFields").size()).isEqualTo(3);
        for (String field : List.of("arguments", "result", "reviewReason")) {
            String value = entry.path(field).stringValue();
            assertThat(value).doesNotContain("�");
            assertThat(value.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(AiChatReviewHistory.MAX_FIELD_BYTES);
            assertThat(content).startsWith(value);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 16 * 1024 + 1, 64 * 1024, 64 * 1024 + 1 })
    void retainsFieldsUpTo64KiBAndTruncatesBeyondIt(int bytes) {
        String content = "a".repeat(bytes);
        var invocation = invocation(1, 7, "DOMAIN", content);
        invocation.succeed(content);
        ReflectionTestUtils.setField(invocation, "reviewReason", content);
        var snapshot = JSON.readTree(AiChatReviewHistory.encode(JSON, List.of(invocation)));
        var entry = snapshot.path("entries").get(0);
        for (String field : List.of("arguments", "result", "reviewReason"))
            assertThat(entry.path(field).stringValue()).isEqualTo(content.substring(0, Math.min(bytes, 64 * 1024)));

        assertThat(entry.path("truncatedFields").size()).isEqualTo(bytes > 64 * 1024 ? 3 : 0);
        assertThat(snapshot.path("truncated").booleanValue()).isEqualTo(bytes > 64 * 1024);
    }

    @Test
    void enforcesTheSerializedBudgetIncludingJsonEscapingAndPrioritizesRecentEntries() {
        List<AiToolInvocation> entries = new ArrayList<>();
        for (int id = 40; id > 0; id--) {
            var invocation = invocation(id, 7, "DOMAIN", "\n".repeat(16000));
            invocation.succeed("\n".repeat(16000));
            entries.add(invocation);
        }
        String encoded = AiChatReviewHistory.encode(JSON, entries);
        assertThat(encoded.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(AiChatReviewHistory.MAX_HISTORY_BYTES);
        var snapshot = JSON.readTree(encoded);
        assertThat(snapshot.path("truncated").booleanValue()).isTrue();
        var selected = snapshot.path("entries");
        assertThat(selected.size()).isBetween(1, 39);
        assertThat(selected.get(selected.size() - 1).path("invocationId").longValue()).isEqualTo(40);
    }

    static AiToolInvocation invocation(long id, long turnId, String kind, String arguments) {
        var conversation = new AiConversation("subject", "user", "test", "DIRECT", "AUTO");
        var turn = new AiTurn(conversation, "test", "low");
        ReflectionTestUtils.setField(turn, "id", turnId);
        var invocation = new AiToolInvocation(turn, 1, "call-" + id, "test_tool", arguments, kind, true, true, true, false, true, false, "", false);
        ReflectionTestUtils.setField(invocation, "id", id);
        return invocation;
    }
}
