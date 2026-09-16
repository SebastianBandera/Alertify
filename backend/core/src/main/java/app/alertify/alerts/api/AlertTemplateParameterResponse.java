package app.alertify.alerts.api;

import java.time.Instant;
import java.util.List;

import app.alertify.alerts.template.annotation.AlertParameterSource;

/**
 * Parameter metadata exposed by the backend. Options are suggestions when
 * binding is allowed and an exhaustive list when it is disabled.
 */
public record AlertTemplateParameterResponse(
    Long id,
    long version,
    String key,
    String labelKey,
    String descriptionKey,
    String javaType,
    List<String> options,
    boolean bindingAllowed,
    boolean writableBindingRequired,
    String defaultValue,
    boolean multiline,
    int order,
    boolean required,
    List<AlertParameterSource> allowedSources,
    List<String> allowedConfigurationValueTypes,
    List<String> allowedSecretValueTypes,
    Instant createdAt,
    Instant updatedAt
) {
}
