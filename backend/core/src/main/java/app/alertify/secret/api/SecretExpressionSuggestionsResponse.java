package app.alertify.secret.api;

import java.util.List;

/** Names an expression secret may reference, for editor autocompletion. */
public record SecretExpressionSuggestionsResponse(
    List<String> configurations,
    List<String> secrets,
    List<String> environmentVariables,
    List<String> utilities,
    List<String> utilityFunctions
) {
}
