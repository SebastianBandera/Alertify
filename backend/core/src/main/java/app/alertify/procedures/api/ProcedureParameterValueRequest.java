package app.alertify.procedures.api;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ProcedureParameterValueRequest(
    @NotBlank @Size(max = 255) String parameterKey,
    @NotNull AlertParameterSource source,
    @Size(max = 1048576) String textValue,
    @Positive Long configurationId,
    @Positive Long secretId,
    @Positive Long procedureId
) {
    @AssertTrue(message = "exactly one value matching source must be provided")
    public boolean isSourceSelectionValid() {
        if (source == null)
            return true;

        return switch (source) {
            case TEXT -> textValue != null && configurationId == null && secretId == null && procedureId == null;
            case CONFIGURATION -> textValue == null && configurationId != null && secretId == null && procedureId == null;
            case SECRET -> textValue == null && configurationId == null && secretId != null && procedureId == null;
            case PROCEDURE -> textValue == null && configurationId == null && secretId == null && procedureId != null;
        };
    }
}
