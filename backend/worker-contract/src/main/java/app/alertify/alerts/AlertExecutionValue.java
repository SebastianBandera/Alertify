package app.alertify.alerts;

import java.util.Objects;

/** One read-only configuration or secret value selected before worker dispatch. */
public record AlertExecutionValue(AlertExecutionValueSource source, String name, String value) {

    public AlertExecutionValue {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(value, "value must not be null");
        name = name.trim();
        if (name.isEmpty())
            throw new IllegalArgumentException("name must not be blank");
    }
}
