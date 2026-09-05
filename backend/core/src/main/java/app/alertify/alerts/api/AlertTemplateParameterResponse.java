package app.alertify.alerts.api;

import java.time.Instant;
import java.util.List;

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
    String defaultValue,
    boolean multiline,
    int order,
    boolean required,
    Instant createdAt,
    Instant updatedAt
) {
}
