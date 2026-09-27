package app.alertify.alerts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import app.alertify.alerts.template.annotation.AlertParameterSource;

/**
 * Mutable, execution-local context through which an evaluator can publish a
 * compact diagnostic state. A fresh instance is used for every execution.
 */
public final class AlertExecutionContext {

    private String state;
    /** Sources keyed by template parameter name; binding names and IDs are not exposed. */
    private final Map<String, AlertParameterSource> parameterSources;
    /** Execution-scoped values keyed privately so templates cannot enumerate the prepared pool. */
    private final Map<ValueKey, String> preparedValues;

    public AlertExecutionContext() {
        this(null, Map.of());
    }

    public AlertExecutionContext(String state) {
        this(state, Map.of());
    }

    public AlertExecutionContext(String state, Map<String, AlertParameterSource> parameterSources) {
        this(state, parameterSources, List.of());
    }

    public AlertExecutionContext(String state, Map<String, AlertParameterSource> parameterSources, List<AlertExecutionValue> preparedValues) {
        this.state = state == null ? "" : state;
        this.parameterSources = Map.copyOf(Objects.requireNonNull(parameterSources, "parameterSources must not be null"));
        Objects.requireNonNull(preparedValues, "preparedValues must not be null");
        Map<ValueKey, String> values = new LinkedHashMap<>();
        for (AlertExecutionValue preparedValue : preparedValues) {
            AlertExecutionValue value = Objects.requireNonNull(preparedValue, "preparedValues must not contain null");
            ValueKey key = ValueKey.of(value.source(), value.name());
            if (values.putIfAbsent(key, value.value()) != null)
                throw new IllegalArgumentException("Duplicate prepared value '" + value.name() + "'");
        }
        this.preparedValues = Map.copyOf(values);
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state == null ? "" : state;
    }

    public AlertParameterSource getParameterSource(String parameterName) {
        return parameterSources.get(Objects.requireNonNull(parameterName, "parameterName must not be null"));
    }

    /**
     * Returns a value selected by the backend before this execution was sent to
     * the worker. There is deliberately no catalog or collection accessor.
     */
    public String requireValue(AlertExecutionValueSource source, String name) {
        String value = preparedValues.get(ValueKey.of(source, name));
        if (value == null)
            throw new IllegalArgumentException("Value '" + name + "' was not prepared for this execution");

        return value;
    }

    private record ValueKey(AlertExecutionValueSource source, String name) {

        private static ValueKey of(AlertExecutionValueSource source, String name) {
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(name, "name must not be null");
            String normalized = name.trim();
            if (normalized.isEmpty())
                throw new IllegalArgumentException("name must not be blank");

            return new ValueKey(source, normalized.toLowerCase(Locale.ROOT));
        }
    }
}
