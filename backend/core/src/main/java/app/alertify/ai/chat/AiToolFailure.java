package app.alertify.ai.chat;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.api.error.ConflictException;
import app.alertify.jpa.specification.InvalidFilterException;

record AiToolFailure(String code, String message) {

    static AiToolFailure classify(String toolName, RuntimeException exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof app.alertify.ai.memory.AiMemoryAccess.Unavailable unavailable)
                return new AiToolFailure(unavailable.code(), unavailable.getMessage());

            if (cause instanceof app.alertify.api.error.TemplateSelectionException)
                return new AiToolFailure("TOOL_TEMPLATE_SELECTION_INVALID", "Provide a positive template ID or exact fully qualified template key; if both are provided they must identify the same template. Reload its metadata before proposing parameters.");

            if (cause instanceof InvalidFilterException)
                return new AiToolFailure("TOOL_INVALID_FILTER", "The search filter or sort is invalid. Consult this tool's declared fields before retrying."
                        + ("alertify_configuration_search".equals(toolName)
                                ? " Configuration tag membership uses tagId, not tagIds; repeat tagId once per positive ID. tagOperator is OR or AND."
                                : ""));

            if (cause instanceof ResourceNotFoundException)
                return new AiToolFailure("TOOL_RESOURCE_NOT_FOUND", "The requested resource ID was not found in this tool's scope.");

            if ((cause instanceof ConflictException conflict && "VERSION_CONFLICT".equals(conflict.getCode()))
                    || cause instanceof jakarta.persistence.OptimisticLockException
                    || cause instanceof org.springframework.dao.OptimisticLockingFailureException)
                return new AiToolFailure("TOOL_VERSION_CONFLICT", "The execution was rejected because the resource version changed. Reload the definition before proposing another authorized edit; do not replay the stale proposal.");

            if (cause instanceof ConflictException conflict && "PIPE_TAG_IN_USE".equals(conflict.getCode()))
                return new AiToolFailure("PIPE_TAG_IN_USE", "The tag is assigned to pipes. No deletion or detachment was performed. Preserve the associations and report this rejection.");

        }
        return new AiToolFailure("TOOL_EXECUTION_FAILED", "Tool execution failed (" + exception.getClass().getSimpleName() + ")");
    }
}
