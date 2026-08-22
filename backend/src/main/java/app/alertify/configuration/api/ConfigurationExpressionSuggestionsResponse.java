package app.alertify.configuration.api;

import java.util.List;

public record ConfigurationExpressionSuggestionsResponse(
    List<String> configurations,
    List<String> environmentVariables
) {
}
