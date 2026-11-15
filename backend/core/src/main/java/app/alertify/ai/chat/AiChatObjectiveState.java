package app.alertify.ai.chat;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import app.alertify.worker.contract.SecretValueSanitizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Structured state only. Model prose and page content are never execution evidence. */
final class AiChatObjectiveState {
    static final String TOOL = "alertify_objective_update";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED");

    private AiChatObjectiveState() { }

    static JsonNode read(String document) { return document == null || document.isBlank() ? null : JSON.readTree(document); }
    static long revision(JsonNode value) { return value == null ? 0 : value.path("revision").asLong(); }
    static boolean pending(JsonNode value) { return value != null && !TERMINAL.contains(value.path("status").asString()); }
    static boolean canComplete(JsonNode value) { return value == null || Set.of("COMPLETED", "CANCELLED", "NEEDS_DECISION", "BLOCKED").contains(value.path("status").asString()); }

    static Change change(JsonNode current, JsonNode input, long sourceMessageId, long turnId, List<AiToolInvocation> evidence, List<String> sensitive) {
        if (input == null || !input.isObject() || !input.path("expectedRevision").isIntegralNumber() || !input.path("expectedRevision").canConvertToLong()
                || input.path("expectedRevision").asLong(-1) != revision(current))
            throw new IllegalArgumentException("OBJECTIVE_REVISION_CONFLICT: read the current objective revision before updating it");

        String operation = input.path("operation").asString();
        String kind = input.path("kind").asString();
        if (!Set.of("ASSESS", "UPDATE").contains(operation) || !Set.of("QUESTION", "WORK", "RESUME", "REVISE", "CANCEL").contains(kind))
            throw new IllegalArgumentException("Invalid structured objective operation");

        if (operation.equals("ASSESS") && kind.equals("QUESTION")) return new Change(current, false, false);

        if (operation.equals("ASSESS") && kind.equals("CANCEL")) {
            if (current == null) return new Change(null, false, false);

            ObjectNode cancelled = copy(current);
            cancelled.put("status", "CANCELLED").put("needsReconciliation", false);
            cancelled.put("reason", text(input, "reason", 1000, sensitive));
            addSource(cancelled, sourceMessageId);
            touch(cancelled);
            return new Change(cancelled, true, false);
        }
        boolean assessment = operation.equals("ASSESS");
        boolean replacing = assessment && (kind.equals("WORK") || kind.equals("REVISE"));
        if (assessment && kind.equals("WORK") && pending(current))
            throw new IllegalArgumentException("An objective is pending. Assess the latest USER request as RESUME, REVISE, CANCEL or QUESTION");

        if (assessment && kind.equals("RESUME") && !pending(current))
            throw new IllegalArgumentException("No pending objective can be resumed");

        if (!assessment && current == null)
            throw new IllegalArgumentException("Assess a multi-step USER task before updating an objective");

        if (!assessment && TERMINAL.contains(current.path("status").asString()))
            throw new IllegalArgumentException("A closed objective requires a new USER assessment");

        String status = input.path("status").asString();
        if (assessment && status.equals("COMPLETED"))
            throw new IllegalArgumentException("An assessment cannot claim completion before performing and verifying work");

        if (!Set.of("ACTIVE", "COMPLETED", "NEEDS_DECISION", "BLOCKED").contains(status))
            throw new IllegalArgumentException("Invalid objective status");

        JsonNode steps = assessment && kind.equals("RESUME") ? current.path("steps") : input.path("steps");
        if (!steps.isArray() || steps.isEmpty() || steps.size() > 100)
            throw new IllegalArgumentException("An objective requires between 1 and 100 steps");

        if (assessment && kind.equals("WORK") && steps.size() == 1) return new Change(current, false, false);

        ObjectNode result = replacing ? JSON.createObjectNode().put("id", UUID.randomUUID().toString()).put("revision", revision(current)) : copy(current);
        if (assessment && kind.equals("REVISE") && current != null) result.put("id", current.path("id").asString());
        if (replacing && current != null && current.path("sourceMessageIds").isArray()) result.set("sourceMessageIds", current.path("sourceMessageIds").deepCopy());
        result.put("summary", !assessment || kind.equals("RESUME") ? current.path("summary").asString() : text(input, "summary", 1000, sensitive));
        result.put("status", status).put("reason", text(input, "reason", 1000, sensitive));
        var nextSteps = JSON.createArrayNode();
        Set<String> identifiers = new HashSet<>();
        boolean freshRead = false;
        for (JsonNode step : steps) {
            String id = step.path("id").asString();
            String state = step.path("status").asString();
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !identifiers.add(id) || !Set.of("PENDING", "APPLIED", "VERIFIED").contains(state))
                throw new IllegalArgumentException("Invalid or duplicate objective step");

            JsonNode prior = current == null ? null : current.path("steps").valueStream().filter(old -> old.path("id").asString().equals(id)).findFirst().orElse(null);
            if (assessment && !kind.equals("RESUME") && !state.equals("PENDING"))
                throw new IllegalArgumentException("New or revised steps start PENDING; reconcile recorded actions before marking them applied");

            if (!step.path("evidenceInvocationIds").isArray() || step.path("evidenceInvocationIds").size() > 40)
                throw new IllegalArgumentException("Each step requires a bounded evidence invocation list");

            var checkedIds = JSON.createArrayNode();
            boolean succeededWrite = false;
            boolean succeededRead = false;
            Instant lastWrite = null;
            Instant lastRead = null;
            boolean changedEvidence = prior == null || !prior.path("status").asString().equals(state) || !prior.path("evidenceInvocationIds").equals(step.path("evidenceInvocationIds"));
            for (JsonNode idNode : step.path("evidenceInvocationIds")) {
                if (!idNode.isIntegralNumber() || !idNode.canConvertToLong() || idNode.asLong() < 1)
                    throw new IllegalArgumentException("Evidence IDs must identify successful domain tool invocations");

                long evidenceId = idNode.asLong();
                AiToolInvocation invocation = evidence.stream().filter(value -> value.getId().equals(evidenceId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Objective evidence is unavailable or outside this conversation"));
                if ((!"DOMAIN".equals(invocation.getKind()) && !trustedPatchReadback(invocation, evidence)) || !"SUCCEEDED".equals(invocation.getStatus()))
                    throw new IllegalArgumentException("Only successful domain tool results are objective evidence");

                boolean currentTurn = invocation.getTurn().getId().equals(turnId);
                succeededRead |= invocation.isReadOnly() && (!changedEvidence || currentTurn);
                freshRead |= invocation.isReadOnly() && currentTurn;
                succeededWrite |= !invocation.isReadOnly();
                if (invocation.getFinishedAt() != null) {
                    if (invocation.isReadOnly() && (!changedEvidence || currentTurn) && (lastRead == null || invocation.getFinishedAt().isAfter(lastRead))) lastRead = invocation.getFinishedAt();
                    if (!invocation.isReadOnly() && (lastWrite == null || invocation.getFinishedAt().isAfter(lastWrite))) lastWrite = invocation.getFinishedAt();
                }
                checkedIds.add(evidenceId);
            }
            if (!assessment && prior != null) {
                for (JsonNode previousId : prior.path("evidenceInvocationIds")) {
                    if (checkedIds.valueStream().noneMatch(idNode -> idNode.asLong() == previousId.asLong()))
                        throw new IllegalArgumentException("Keep prior step evidence when adding reconciliation or verification");
                }
            }
            if (state.equals("APPLIED") && !succeededWrite)
                throw new IllegalArgumentException("An applied step requires a recorded successful write");

            if (state.equals("VERIFIED") && !succeededRead)
                throw new IllegalArgumentException("A newly verified step requires a successful domain read from this turn");

            if (state.equals("VERIFIED") && succeededWrite && (lastWrite == null || lastRead == null || lastRead.isBefore(lastWrite)))
                throw new IllegalArgumentException("Verify a successful write with a read performed after that write");

            String title = !assessment && prior != null ? prior.path("title").asString() : text(step, "title", 300, sensitive);
            nextSteps.add(JSON.createObjectNode().put("id", id).put("title", title).put("status", state).set("evidenceInvocationIds", checkedIds));
        }
        if (!assessment && !identifiers.equals(current.path("steps").valueStream().map(step -> step.path("id").asString()).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("Changing the objective steps requires a new USER assessment with REVISE");

        if (status.equals("COMPLETED") && nextSteps.valueStream().anyMatch(step -> !step.path("status").asString().equals("VERIFIED")))
            throw new IllegalArgumentException("Every objective step must be verified before completing it");

        if (Set.of("NEEDS_DECISION", "BLOCKED").contains(status) && result.path("reason").asString().isBlank())
            throw new IllegalArgumentException("A blocked objective or required decision needs an explanation");

        result.set("steps", nextSteps);
        boolean reconcile = (current != null && current.path("needsReconciliation").asBoolean()) || (assessment && kind.equals("RESUME"));
        result.put("needsReconciliation", reconcile && !freshRead);
        if (status.equals("COMPLETED") && result.path("needsReconciliation").asBoolean())
            throw new IllegalArgumentException("Reconcile persisted state with current reads before completing the resumed objective");

        if (assessment) addSource(result, sourceMessageId);
        touch(result);
        return new Change(result, true, true);
    }

    static JsonNode interrupted(JsonNode current, String reason) {
        if (!pending(current)) return current;

        ObjectNode value = copy(current);
        value.put("status", "INTERRUPTED").put("needsReconciliation", true).put("reason", reason);
        touch(value);
        return value;
    }

    private static ObjectNode copy(JsonNode value) { return (ObjectNode) value.deepCopy(); }
    private static void touch(ObjectNode value) { value.put("revision", revision(value) + 1).put("updatedAt", Instant.now().toString()); }

    private static boolean trustedPatchReadback(AiToolInvocation read, List<AiToolInvocation> evidence) {
        if (!"INTERNAL".equals(read.getKind()) || !read.isReadOnly() || read.getCallId() == null || !read.getCallId().matches("patch-read:[1-9][0-9]*")) return false;

        try {
            long patchId = Long.parseLong(read.getCallId().substring("patch-read:".length()));
            AiToolInvocation patch = evidence.stream().filter(value -> value.getId().equals(patchId)).findFirst().orElse(null);
            if (patch == null || !"DOMAIN".equals(patch.getKind()) || !"SUCCEEDED".equals(patch.getStatus()) || patch.isReadOnly()
                    || !patch.getToolName().endsWith("_patch") || !read.getToolName().equals(patch.getToolName().replaceFirst("_patch$", "_get"))
                    || !read.getTurn().getId().equals(patch.getTurn().getId()) || patch.getFinishedAt() == null || read.getFinishedAt() == null
                    || read.getFinishedAt().isBefore(patch.getFinishedAt())) return false;

            JsonNode expected = JSON.readTree(patch.getResult());
            JsonNode actual = JSON.readTree(read.getResult());
            return expected.isObject() && actual.isObject() && expected.path("id").isIntegralNumber() && expected.path("id").asLong() > 0
                    && expected.path("id").equals(actual.path("id")) && "MATCHED".equals(AiPatchReadback.compare(expected, actual));
        } catch (RuntimeException invalidEvidence) {
            return false;
        }
    }

    private static void addSource(ObjectNode value, long messageId) {
        if (messageId < 1) throw new IllegalArgumentException("An objective requires a persisted USER source message");

        var sources = value.withArray("sourceMessageIds");
        if (sources.valueStream().noneMatch(id -> id.asLong() == messageId)) sources.add(messageId);
    }

    private static String text(JsonNode node, String field, int maximum, List<String> sensitive) {
        if (!node.path(field).isString() || node.path(field).asString().length() > maximum)
            throw new IllegalArgumentException("Invalid bounded objective text");

        String value = SecretValueSanitizer.sanitize(node.path(field).asString().strip(), sensitive);
        return value.replaceAll("(?i)(?:Authorization\\s*:\\s*)?(?:Bearer|Basic)\\s+[^\\s]+", "[REDACTED]")
                .replaceAll("(?i)(password|token|secret|api[_ -]?key)\\s*[=:]\\s*[^\\s,;]+", "$1=[REDACTED]");
    }

    record Change(JsonNode document, boolean changed, boolean participates) { }
}
