package app.alertify.alerts;

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

    public AlertExecutionContext() {
        this(null, Map.of());
    }

    public AlertExecutionContext(String state) {
        this(state, Map.of());
    }

    public AlertExecutionContext(String state, Map<String, AlertParameterSource> parameterSources) {
        this.state = state == null ? "" : state;
        this.parameterSources = Map.copyOf(Objects.requireNonNull(parameterSources, "parameterSources must not be null"));
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
}
