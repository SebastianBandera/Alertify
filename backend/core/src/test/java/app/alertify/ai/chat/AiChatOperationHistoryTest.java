package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.json.JsonMapper;

class AiChatOperationHistoryTest {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Instant base = Instant.parse("2026-10-12T15:30:00Z");

    @Test
    void aPreWriteReadAndTheUpdateResponseDoNotCountAsAPostWriteRead() {
        var read = operation(1, 20, "alertify_alert_get", true, "SUCCEEDED", 1, "{\"id\":25}", "{\"id\":25}");
        var write = operation(2, 20, "alertify_alert_update", false, "SUCCEEDED", 3, "{\"id\":25}", "{\"id\":25}");

        var result = summarize(List.of(write, read));

        assertThat(result.operations().getLast().postWriteReadObservation()).isEqualTo("NOT_OBSERVED_IN_WINDOW");
        assertThat(result.operations().getLast().subsequentReads()).isEmpty();
        assertThat(result.operations().getLast().fieldComparison()).isEqualTo("NOT_RECORDED");
    }

    @Test
    void anExactPostWriteGetRecordsAReadWithoutCertifyingFieldComparison() {
        var write = operation(1, 20, "alertify_alert_update", false, "SUCCEEDED", 1, "{\"id\":25}", "{\"id\":25}");
        var read = operation(2, 20, "alertify_alert_get", true, "SUCCEEDED", 3, "{\"id\":25}", "{\"id\":25,\"enabled\":false}");

        var result = summarize(List.of(read, write)).operations().getFirst();

        assertThat(result.resourceId()).isEqualTo(25L);
        assertThat(result.postWriteReadObservation()).isEqualTo("MATCHING_SUCCESSFUL_READ");
        assertThat(result.subsequentReads()).extracting(AiChatOperationHistory.ReadEvidence::invocationId).containsExactly(2L);
        assertThat(result.fieldComparison()).isEqualTo("NOT_RECORDED");
    }

    @Test
    void aProcedureSearchCanRecordAnExactIdOnAnyReturnedPage() {
        var write = operation(1, 20, "alertify_procedure_create", false, "SUCCEEDED", 1, "{}", "{\"id\":25}");
        var read = operation(2, 20, "alertify_procedure_search", true, "SUCCEEDED", 3, "{}", "{\"content\":[{\"id\":24},{\"id\":25}],\"page\":2,\"totalPages\":5}");

        var result = summarize(List.of(read, write)).operations().getFirst();

        assertThat(result.resourceId()).isEqualTo(25L);
        assertThat(result.postWriteReadObservation()).isEqualTo("MATCHING_SUCCESSFUL_READ");
        assertThat(result.fieldComparison()).isEqualTo("NOT_RECORDED");
    }

    @Test
    void anotherFamilyTagIdTurnOrFailedReadDoesNotProvideEvidence() {
        var write = operation(1, 20, "alertify_pipe_update", false, "SUCCEEDED", 1, "{\"id\":25}", "{\"id\":25}");
        var otherFamily = operation(2, 20, "alertify_alert_get", true, "SUCCEEDED", 3, "{\"id\":25}", "{\"id\":25}");
        var tag = operation(3, 20, "alertify_pipe_tag_get", true, "SUCCEEDED", 5, "{\"id\":25}", "{\"id\":25}");
        var otherId = operation(4, 20, "alertify_pipe_get", true, "SUCCEEDED", 7, "{\"id\":26}", "{\"id\":26}");
        var failed = operation(5, 20, "alertify_pipe_get", true, "FAILED", 9, "{\"id\":25}", "{\"id\":25}");
        var otherTurn = operation(6, 21, "alertify_pipe_get", true, "SUCCEEDED", 11, "{\"id\":25}", "{\"id\":25}");

        var result = summarize(List.of(otherTurn, failed, otherId, tag, otherFamily, write)).operations().getFirst();

        assertThat(result.subsequentReads()).isEmpty();
        assertThat(result.postWriteReadObservation()).isEqualTo("NOT_OBSERVED_IN_WINDOW");
    }

    @Test
    void aGetWithAMismatchingResultOrAnEmptySearchDoesNotCount() {
        var write = operation(1, 20, "alertify_configuration_update", false, "SUCCEEDED", 1, "{\"id\":25}", "{\"id\":25}");
        var mismatch = operation(2, 20, "alertify_configuration_get", true, "SUCCEEDED", 3, "{\"id\":25}", "{\"id\":26}");
        var empty = operation(3, 20, "alertify_configuration_search", true, "SUCCEEDED", 5, "{}", "{\"content\":[],\"totalPages\":0}");

        assertThat(summarize(List.of(empty, mismatch, write)).operations().getFirst().subsequentReads()).isEmpty();
    }

    @Test
    void aLaterWriteEndsTheEarlierObservationWindow() {
        var first = operation(1, 20, "alertify_alert_update", false, "SUCCEEDED", 1, "{\"id\":25}", "{\"id\":25}");
        var second = operation(2, 20, "alertify_alert_update", false, "SUCCEEDED", 3, "{\"id\":25}", "{\"id\":25}");
        var read = operation(3, 20, "alertify_alert_get", true, "SUCCEEDED", 5, "{\"id\":25}", "{\"id\":25}");

        var result = summarize(List.of(read, second, first));

        assertThat(result.operations().getFirst().subsequentReads()).isEmpty();
        assertThat(result.operations().get(1).subsequentReads()).hasSize(1);
    }

    @Test
    void aFailedGetAfterDeleteNeverCertifiesAbsence() {
        var write = operation(1, 20, "alertify_hook_delete", false, "SUCCEEDED", 1, "{\"id\":25}", null);
        var read = operation(2, 20, "alertify_hook_get", true, "FAILED", 3, "{\"id\":25}", "Failed: permission denied");

        var result = summarize(List.of(read, write)).operations().getFirst();

        assertThat(result.postWriteReadObservation()).isEqualTo("NOT_ASSESSED");
        assertThat(result.fieldComparison()).isEqualTo("NOT_RECORDED");
    }

    @Test
    void paginationKeepsTheNewestEntriesChronologicalAndExposesTheOlderCursor() {
        var first = operation(1, 20, "alertify_hook_get", true, "SUCCEEDED", 1, "{\"id\":25}", "{\"id\":25}");
        var second = operation(2, 20, "alertify_hook_get", true, "SUCCEEDED", 3, "{\"id\":25}", "{\"id\":25}");
        var third = operation(3, 21, "alertify_hook_get", true, "SUCCEEDED", 5, "{\"id\":25}", "{\"id\":25}");

        var result = AiChatOperationHistory.summarize(json, 21L, List.of(third, second, first), 2);

        assertThat(result.currentTurnId()).isEqualTo(21L);
        assertThat(result.truncated()).isTrue();
        assertThat(result.nextBeforeInvocationId()).isEqualTo(2L);
        assertThat(result.operations()).extracting(AiChatOperationHistory.Operation::invocationId).containsExactly(2L, 3L);
    }

    @Test
    void metadataNeverIncludesRawArgumentsResultsReasonsOrProtectedValues() {
        var write = operation(1, 20, "alertify_configuration_update", false, "SUCCEEDED", 1,
                "{\"id\":25,\"request\":{\"stringValue\":\"PRIVATE-ARGUMENT\"}}", "{\"id\":25,\"stringValue\":\"PRIVATE-RESULT\"}");
        when(write.getReviewReason()).thenReturn("PRIVATE-REVIEW");

        String result = json.writeValueAsString(summarize(List.of(write)));

        assertThat(result).doesNotContain("PRIVATE-ARGUMENT", "PRIVATE-RESULT", "PRIVATE-REVIEW", "stringValue", "arguments", "result");
        assertThat(result).contains("2026-10-12T15:30:00Z", "2026-10-12T15:30:01Z", "2026-10-12T15:30:02Z", "UTC");
    }

    @Test
    void malformedOrNonPositiveIdsNeverBecomeResourceEvidence() {
        var write = operation(1, 20, "alertify_alert_update", false, "SUCCEEDED", 1, "{truncated", "{\"id\":25}");
        var read = operation(2, 20, "alertify_alert_get", true, "SUCCEEDED", 3, "{\"id\":0}", "{\"id\":0}");

        var result = summarize(List.of(read, write));

        assertThat(result.operations()).extracting(AiChatOperationHistory.Operation::resourceId).containsOnlyNulls();
        assertThat(result.operations().getFirst().subsequentReads()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "FAILED", "REJECTED", "CANCELLED", "INTERRUPTED", "PENDING_APPROVAL" })
    void unsuccessfulWritesKeepTheirActualStatusAndNeverClaimVerification(String status) {
        var write = operation(1, 20, "alertify_pipe_update", false, status, 1, "{\"id\":25}", "error");

        var result = summarize(List.of(write)).operations().getFirst();

        assertThat(result.status()).isEqualTo(status);
        assertThat(result.postWriteReadObservation()).isEqualTo("NOT_ASSESSED");
        assertThat(result.fieldComparison()).isEqualTo("NOT_RECORDED");
    }

    @Test
    void messageMetadataKeepsItsActualTurnDateAndOriginalContent() {
        AiTurn turn = mock(AiTurn.class);
        when(turn.getId()).thenReturn(20L);
        when(turn.getCreatedAt()).thenReturn(base);
        AiMessage message = new AiMessage(mock(AiConversation.class), turn, "ASSISTANT", "Previously verified");
        ReflectionTestUtils.setField(message, "createdAt", base.plusSeconds(15));

        assertThat(AiChatOperationHistory.messageContent(message)).isEqualTo(
                "[Alertify message metadata: turnId=20, turnStartedAt=2026-10-12T15:30:00Z, messageCreatedAt=2026-10-12T15:30:15Z, timeZone=UTC]\nPreviously verified");
    }

    private AiChatOperationHistory.Snapshot summarize(List<AiToolInvocation> operations) {
        return AiChatOperationHistory.summarize(json, 21L, operations, 40);
    }

    private AiToolInvocation operation(long id, long turnId, String tool, boolean readOnly, String status, int seconds, String arguments, String result) {
        AiTurn turn = mock(AiTurn.class);
        when(turn.getId()).thenReturn(turnId);
        when(turn.getCreatedAt()).thenReturn(base);
        when(turn.getFinishedAt()).thenReturn(base.plusSeconds(100));
        when(turn.getStatus()).thenReturn("COMPLETED");
        AiToolInvocation invocation = mock(AiToolInvocation.class);
        when(invocation.getId()).thenReturn(id);
        when(invocation.getTurn()).thenReturn(turn);
        when(invocation.getToolName()).thenReturn(tool);
        when(invocation.isReadOnly()).thenReturn(readOnly);
        when(invocation.getStatus()).thenReturn(status);
        when(invocation.getCreatedAt()).thenReturn(base.plusSeconds(seconds));
        when(invocation.getFinishedAt()).thenReturn(base.plusSeconds(seconds + 1));
        when(invocation.getArguments()).thenReturn(arguments);
        when(invocation.getResult()).thenReturn(result);
        return invocation;
    }
}
