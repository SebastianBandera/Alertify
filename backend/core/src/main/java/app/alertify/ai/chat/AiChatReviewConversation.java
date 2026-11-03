package app.alertify.ai.chat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/** Ephemeral, attributed conversational data for review; never log or persist the snapshot. */
final class AiChatReviewConversation {
    static final int MAX_MESSAGES = 40;
    static final int MAX_CONTENT_BYTES = 16 * 1024;
    static final int MAX_CONTEXT_BYTES = 128 * 1024;
    static final String UNAVAILABLE = "{\"available\":false,\"truncated\":false,\"messages\":[]}";

    private AiChatReviewConversation() {
    }

    static String encode(JsonMapper json, List<AiMessage> newestFirst) {
        List<Message> messages = new ArrayList<>();
        boolean truncated = newestFirst.size() > MAX_MESSAGES;
        for (AiMessage message : newestFirst.stream().limit(MAX_MESSAGES).toList()) {
            String content = bounded(message.getContent());
            boolean contentTruncated = !content.equals(message.getContent());
            messages.add(new Message(message.getId(), message.getTurnId(), message.getRole(), message.getCreatedAt(), content, contentTruncated));
            if (json.writeValueAsString(new Snapshot(true, false, messages)).getBytes(StandardCharsets.UTF_8).length > MAX_CONTEXT_BYTES) {
                messages.removeLast();
                truncated = true;
                break;
            }
            truncated |= contentTruncated;
        }
        Collections.reverse(messages);
        return json.writeValueAsString(new Snapshot(true, truncated, messages));
    }

    private static String bounded(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_CONTENT_BYTES)
            return value;

        int end = MAX_CONTENT_BYTES;
        while ((bytes[end] & 0xc0) == 0x80)
            end--;

        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private record Snapshot(boolean available, boolean truncated, List<Message> messages) { }

    private record Message(Long messageId, Long turnId, String role, Instant createdAt, String content, boolean contentTruncated) { }
}
