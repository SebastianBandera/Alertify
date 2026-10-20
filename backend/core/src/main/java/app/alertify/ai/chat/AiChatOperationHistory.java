package app.alertify.ai.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Audit metadata for model context. Raw arguments, results and review reasons are never serialized. */
final class AiChatOperationHistory {
    private static final Pattern RESOURCE_TOOL = Pattern.compile("alertify_(configuration|alert|procedure|pipe|hook)(_tag)?_(create|update|delete|get|search)");

    private AiChatOperationHistory() {
    }

    static Snapshot summarize(JsonMapper json, Long currentTurnId, List<AiToolInvocation> newestFirst, int limit) {
        boolean truncated = newestFirst.size() > limit;
        List<AiToolInvocation> selected = newestFirst.stream().limit(limit).sorted(Comparator.comparing(AiToolInvocation::getId)).toList();
        List<Operation> operations = new ArrayList<>();
        for (AiToolInvocation invocation : selected) {
            Resource resource = resource(json, invocation);
            List<ReadEvidence> reads = subsequentReads(json, selected, invocation, resource);
            String readObservation = invocation.isReadOnly() || !"SUCCEEDED".equals(invocation.getStatus()) || resource == null || "delete".equals(resource.action())
                    ? "NOT_ASSESSED" : reads.isEmpty() ? "NOT_OBSERVED_IN_WINDOW" : "MATCHING_SUCCESSFUL_READ";
            AiTurn turn = invocation.getTurn();
            operations.add(new Operation(invocation.getId(), turn.getId(), turn.getCreatedAt(), turn.getFinishedAt(), turn.getStatus(),
                    invocation.getToolName(), invocation.getStatus(), invocation.getDiagnosticCode(), invocation.isReadOnly(), invocation.getCreatedAt(), invocation.getFinishedAt(),
                    resource == null ? null : resource.family(), resource == null ? null : resource.id(), readObservation, reads, "NOT_RECORDED"));
        }
        Long beforeId = truncated && !selected.isEmpty() ? selected.getFirst().getId() : null;
        return new Snapshot(currentTurnId, "UTC", truncated, beforeId, operations);
    }

    static String messageContent(AiMessage message) {
        return "[Alertify message metadata: turnId=" + message.getTurnId() + ", turnStartedAt=" + message.getTurnCreatedAt()
                + ", messageCreatedAt=" + message.getCreatedAt() + ", timeZone=UTC]\n" + message.getContent();
    }

    private static List<ReadEvidence> subsequentReads(JsonMapper json, List<AiToolInvocation> selected, AiToolInvocation write, Resource resource) {
        if (write.isReadOnly() || !"SUCCEEDED".equals(write.getStatus()) || write.getFinishedAt() == null || resource == null
                || resource.id() == null || !("create".equals(resource.action()) || "update".equals(resource.action())))
            return List.of();

        List<ReadEvidence> reads = new ArrayList<>();
        for (AiToolInvocation candidate : selected) {
            if (candidate.getId() <= write.getId() || !candidate.getTurn().getId().equals(write.getTurn().getId()) || candidate.getCreatedAt().isBefore(write.getFinishedAt()))
                continue;

            // A later write ends this observation window; its result requires a new verification.
            if (!candidate.isReadOnly() && "SUCCEEDED".equals(candidate.getStatus()))
                break;

            Resource read = resource(json, candidate);
            if (candidate.isReadOnly() && "SUCCEEDED".equals(candidate.getStatus()) && candidate.getFinishedAt() != null && read != null
                    && resource.family().equals(read.family()) && returnsResource(json, candidate, read, resource.id()))
                reads.add(new ReadEvidence(candidate.getId(), candidate.getToolName(), candidate.getFinishedAt()));
        }
        return List.copyOf(reads);
    }

    private static boolean returnsResource(JsonMapper json, AiToolInvocation invocation, Resource read, Long id) {
        JsonNode result = parse(json, invocation.getResult());
        if (result == null)
            return false;

        if ("get".equals(read.action()))
            return id.equals(read.id()) && id.equals(positiveId(result.path("id")));

        if ("search".equals(read.action()) && result.path("content").isArray())
            for (JsonNode item : result.path("content"))
                if (id.equals(positiveId(item.path("id"))))
                    return true;

        return false;
    }

    private static Resource resource(JsonMapper json, AiToolInvocation invocation) {
        return resource(json, invocation, invocation.getResult(), "SUCCEEDED".equals(invocation.getStatus()));
    }

    static Resource resource(JsonMapper json, AiToolInvocation invocation, String output, boolean succeeded) {
        var matcher = RESOURCE_TOOL.matcher(invocation.getToolName());
        if (!matcher.matches())
            return null;

        String family = matcher.group(1) + (matcher.group(2) == null ? "" : "_tag");
        String action = matcher.group(3);
        JsonNode arguments = parse(json, invocation.getArguments());
        Long id = arguments == null ? null : positiveId(arguments.path("id"));
        if ("create".equals(action) && succeeded) {
            JsonNode result = parse(json, output);
            id = result == null ? null : positiveId(result.path("id"));
        }
        return new Resource(family, action, id);
    }

    private static JsonNode parse(JsonMapper json, String value) {
        if (value == null || value.isBlank())
            return null;

        try {
            return json.readTree(value);
        } catch (RuntimeException exception) {
            // Historical output can be text, truncated JSON or an error. Never echo it as a diagnostic.
            return null;
        }
    }

    private static Long positiveId(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0 ? value.longValue() : null;
    }

    record Resource(String family, String action, Long id) {
    }

    record Snapshot(Long currentTurnId, String timeZone, boolean truncated, Long nextBeforeInvocationId, List<Operation> operations) {
    }

    record Operation(Long invocationId, Long turnId, Instant turnStartedAt, Instant turnFinishedAt, String turnStatus,
            String toolName, String status, String diagnosticCode, boolean readOnly, Instant proposedAt, Instant finishedAt,
            String resourceFamily, Long resourceId, String postWriteReadObservation, List<ReadEvidence> subsequentReads, String fieldComparison) {
    }

    record ReadEvidence(Long invocationId, String toolName, Instant finishedAt) {
    }
}
