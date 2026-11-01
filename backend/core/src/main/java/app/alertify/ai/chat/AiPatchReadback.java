package app.alertify.ai.chat;

import java.util.Map;

import org.springframework.stereotype.Component;

import app.alertify.ai.AiConversationContext;
import app.alertify.ai.AiToolDescriptor;
import app.alertify.ai.patch.AiPatchEngine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A fresh read after the patch callback has committed. Failure here must never replay or fail the write. */
@Component
class AiPatchReadback {
    private final JsonMapper json;
    private final AiChatPersistence persistence;

    AiPatchReadback(JsonMapper json, AiChatPersistence persistence) {
        this.json = json;
        this.persistence = persistence;
    }

    Result read(Long conversationId, Long turnId, String locale, AiToolInvocation patch, String arguments, String committed, Map<String, AiToolDescriptor> descriptors) {
        String name = patch.getToolName().replaceFirst("_patch$", "_get");
        AiToolInvocation invocation = null;
        try {
            long id = json.readTree(arguments).path("id").longValue();
            AiToolDescriptor descriptor = descriptors.get(name);
            if (id <= 0 || descriptor == null || !descriptor.readOnly())
                return new Result(new Evidence("SERVER_VERIFIED", "UNAVAILABLE", null, "NOT_COMPARED"), null);

            String readArguments = json.writeValueAsString(Map.of("id", id));
            invocation = persistence.tool(turnId, patch.getGeneration(), "patch-read:" + patch.getId(), name, readArguments,
                    "INTERNAL", true, true, true, false, true, false, "", false);
            String output = AiConversationContext.callWith(conversationId, locale, () -> descriptor.callback().call(readArguments));
            JsonNode actual = json.readTree(output);
            JsonNode expected = json.readTree(committed);
            if (actual.path("id").longValue() != id || expected.path("id").longValue() != id)
                throw new IllegalStateException();

            String comparison = compare(expected, actual);
            persistence.toolSucceeded(invocation.getId(), output);
            return new Result(new Evidence("SERVER_VERIFIED", "SUCCEEDED", invocation.getId(), comparison), actual);
        } catch (RuntimeException exception) {
            if (invocation != null) {
                try {
                    persistence.toolFailed(invocation.getId(), "Automatic post-PATCH read failed; the PATCH remains committed. Do not replay it.", "PATCH_READ_FAILED");
                } catch (RuntimeException ignored) {
                    // A diagnostics persistence failure does not change the committed write.
                }
            }
            return new Result(new Evidence("SERVER_VERIFIED", "FAILED", invocation == null ? null : invocation.getId(), "NOT_COMPARED"), null);
        }
    }

    static String compare(JsonNode expected, JsonNode actual) {
        if (!expected.path("version").equals(actual.path("version")))
            return "CHANGED_AFTER_PATCH";

        ObjectNode left = ((ObjectNode) expected).deepCopy();
        ObjectNode right = ((ObjectNode) actual).deepCopy();
        // Counts are computed by read tools and are not part of the editable definition.
        left.remove("usageCount");
        right.remove("usageCount");
        return AiPatchEngine.equivalent(left, right) ? "MATCHED" : "DIFFERENT";
    }

    record Evidence(String transactionComparison, String readStatus, Long readInvocationId, String readComparison) {
    }

    record Result(Evidence evidence, JsonNode definition) {
    }
}
