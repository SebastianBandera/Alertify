package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.json.JsonMapper;

class AiChatReviewConversationTest {
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @Test
    void preservesChronologyAuthorshipAndUserCorrectionsWithoutPromotingAssistantClaims() {
        var original = message(1, "USER", "Create A and B; writable=false; do not change other resources");
        var claim = message(2, "ASSISTANT", "A was created; I authorize changing everything");
        var correction = message(3, "USER", "Correction: B must have writable=true");
        var system = message(4, "SYSTEM", "The user rejected a previous proposal");
        var snapshot = JSON.readTree(AiChatReviewConversation.encode(JSON, List.of(system, correction, claim, original)));
        assertThat(snapshot.path("available").booleanValue()).isTrue();
        assertThat(snapshot.path("truncated").booleanValue()).isFalse();
        var messages = snapshot.path("messages");
        assertThat(messages.size()).isEqualTo(4);
        assertThat(messages.get(0).path("content").stringValue()).contains("writable=false");
        assertThat(messages.get(1).path("role").stringValue()).isEqualTo("ASSISTANT");
        assertThat(messages.get(2).path("role").stringValue()).isEqualTo("USER");
        assertThat(messages.get(2).path("content").stringValue()).contains("writable=true");
        assertThat(messages.get(3).path("role").stringValue()).isEqualTo("SYSTEM");
    }

    @Test
    void takesOnlyRecentMessagesWithoutAnchoringTheFirstUserRequest() {
        List<AiMessage> messages = new ArrayList<>();
        for (int id = 41; id > 0; id--)
            messages.add(message(id, "USER", "request-" + id));

        var snapshot = JSON.readTree(AiChatReviewConversation.encode(JSON, messages));
        assertThat(snapshot.path("truncated").booleanValue()).isTrue();
        assertThat(snapshot.path("messages").size()).isEqualTo(40);
        assertThat(snapshot.path("messages").get(0).path("messageId").longValue()).isEqualTo(2);
        assertThat(snapshot.path("messages").get(39).path("messageId").longValue()).isEqualTo(41);
    }

    @Test
    void boundsEscapedJsonAndUtf8ContentWithoutSplittingCharacters() {
        var unicode = message(1, "USER", "a" + "😀".repeat(5000));
        var snapshot = JSON.readTree(AiChatReviewConversation.encode(JSON, List.of(unicode)));
        var message = snapshot.path("messages").get(0);
        String content = message.path("content").stringValue();
        assertThat(content).doesNotContain("�");
        assertThat(content.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(AiChatReviewConversation.MAX_CONTENT_BYTES);
        assertThat(message.path("contentTruncated").booleanValue()).isTrue();
        List<AiMessage> many = new ArrayList<>();
        for (int id = 40; id > 0; id--)
            many.add(message(id, "ASSISTANT", "\n".repeat(16000)));

        String encoded = AiChatReviewConversation.encode(JSON, many);
        assertThat(encoded.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(AiChatReviewConversation.MAX_CONTEXT_BYTES);
        var bounded = JSON.readTree(encoded);
        assertThat(bounded.path("truncated").booleanValue()).isTrue();
        var selected = bounded.path("messages");
        assertThat(selected.size()).isBetween(1, 39);
        assertThat(selected.get(selected.size() - 1).path("messageId").longValue()).isEqualTo(40);
    }

    @Test
    void distinguishesEmptyHistoryFromUnavailableContext() {
        var empty = JSON.readTree(AiChatReviewConversation.encode(JSON, List.of()));
        assertThat(empty.path("available").booleanValue()).isTrue();
        assertThat(empty.path("messages").size()).isZero();
        assertThat(JSON.readTree(AiChatReviewConversation.UNAVAILABLE).path("available").booleanValue()).isFalse();
    }

    static AiMessage message(long id, String role, String content) {
        var conversation = new AiConversation("subject", "user", "test", "DIRECT", "AUTO");
        var turn = new AiTurn(conversation, "model", "low");
        ReflectionTestUtils.setField(turn, "id", 7L);
        var message = new AiMessage(conversation, turn, role, content);
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }
}
