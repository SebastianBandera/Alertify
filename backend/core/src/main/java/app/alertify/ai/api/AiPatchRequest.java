package app.alertify.ai.api;

import java.util.List;

import org.springframework.ai.tool.annotation.ToolParam;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AiPatchRequest(
    @NotNull @PositiveOrZero Long version,
    @ToolParam(description = "Ordered atomic operations on the editable definition. Omitted fields remain unchanged. Read the resource first. Before editing/removing/moving an existing parameters/steps/targets/bindings element, test its identity at that index. Paths use JSON Pointer escaping (~0 for ~, ~1 for /); array indices start at zero; add to /- appends. Never patch version, IDs, templates or runtime metadata.")
    @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Operation> operations
) {
    public record Operation(
        @ToolParam(description = "One of add, replace, remove, move, test") @NotBlank String op,
        @ToolParam(description = "JSON Pointer from the editable RESOURCE definition, never from its embedded value. Examples: /description, /parameters/0/textValue, /value/timeout, and /value/days for system configuration DASHBOARD_HISTORY_WINDOW. The embedded days field is NOT /days.") @NotBlank @Size(max = 2000) String path,
        @ToolParam(required = false, description = "Serialized JSON value for add/replace/test, e.g. 60, false, null, or \"text\". Use the string null for an explicit JSON null; omit this argument for remove/move.") @Size(max = 1048576) String valueJson,
        @ToolParam(required = false, description = "Source JSON Pointer for move; otherwise omit or use null") @Size(max = 2000) String from
    ) {
    }
}
