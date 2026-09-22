package app.alertify.secret.api;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/** Credentials of a {@code DB_SECRET} to probe before the secret is saved; never persisted. */
public record DatabaseSecretTestRequest(
    @NotNull JsonNode value
) {
}
