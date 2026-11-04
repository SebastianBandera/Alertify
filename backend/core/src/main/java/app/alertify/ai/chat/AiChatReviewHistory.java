package app.alertify.ai.chat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/** Ephemeral reviewer context. Never persist or log its arguments, results or reasons. */
final class AiChatReviewHistory {
    static final int MAX_ENTRIES = 40;
    static final int MAX_FIELD_BYTES = 16 * 1024;
    static final int MAX_HISTORY_BYTES = 256 * 1024;
    static final String UNAVAILABLE = "{\"available\":false,\"truncated\":false,\"entries\":[]}";

    private AiChatReviewHistory() {
    }

    static String encode(JsonMapper json, List<AiToolInvocation> newestFirst) {
        List<Entry> entries = new ArrayList<>();
        boolean truncated = newestFirst.size() > MAX_ENTRIES;
        for (AiToolInvocation invocation : newestFirst.stream().limit(MAX_ENTRIES).toList()) {
            List<String> truncatedFields = new ArrayList<>();
            Entry entry = new Entry(invocation.getId(), invocation.getTurn().getId(), invocation.getGeneration(),
                    bounded(invocation.getToolName(), "toolName", truncatedFields), invocation.getKind(), invocation.isReadOnly(),
                    invocation.getStatus(), invocation.getCreatedAt(), invocation.getFinishedAt(),
                    bounded(invocation.getArguments(), "arguments", truncatedFields), bounded(invocation.getResult(), "result", truncatedFields),
                    invocation.getDiagnosticCode(), bounded(invocation.getReviewReason(), "reviewReason", truncatedFields),
                    invocation.getApprovalDecision(), invocation.getApprovalActorType(), bounded(invocation.getApprovalActor(), "approvalActor", truncatedFields),
                    bounded(invocation.getApprovalActorSubject(), "approvalActorSubject", truncatedFields), invocation.getApprovalDecidedAt(), List.copyOf(truncatedFields));
            entries.add(entry);
            if (json.writeValueAsString(new Snapshot(true, false, entries)).getBytes(StandardCharsets.UTF_8).length > MAX_HISTORY_BYTES) {
                entries.removeLast();
                truncated = true;
                break;
            }
            truncated |= !truncatedFields.isEmpty();
        }
        Collections.reverse(entries);
        return json.writeValueAsString(new Snapshot(true, truncated, entries));
    }

    private static String bounded(String value, String field, List<String> truncatedFields) {
        if (value == null)
            return null;

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_FIELD_BYTES)
            return value;

        int end = MAX_FIELD_BYTES;
        while ((bytes[end] & 0xc0) == 0x80)
            end--;

        truncatedFields.add(field);
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private record Snapshot(boolean available, boolean truncated, List<Entry> entries) { }

    private record Entry(Long invocationId, Long turnId, long generation, String toolName, String kind, boolean readOnly,
            String status, Instant createdAt, Instant finishedAt, String arguments, String result, String diagnosticCode,
            String reviewReason, String approvalDecision, String approvalActorType, String approvalActor,
            String approvalActorSubject, Instant approvalDecidedAt, List<String> truncatedFields) { }
}
