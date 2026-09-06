package app.alertify.procedures;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import app.alertify.alerts.template.annotation.AlertParameterSource;

/** Immutable execution-local information supplied to a procedure evaluator. */
public final class ProcedureExecutionContext {

    private final Instant now;
    private final Map<String, AlertParameterSource> parameterSources;

    public ProcedureExecutionContext(Instant now, Map<String, AlertParameterSource> parameterSources) {
        this.now = Objects.requireNonNull(now, "now must not be null");
        this.parameterSources = Map.copyOf(Objects.requireNonNull(parameterSources, "parameterSources must not be null"));
    }

    public Instant now() {
        return now;
    }

    public AlertParameterSource getParameterSource(String parameterName) {
        return parameterSources.get(Objects.requireNonNull(parameterName, "parameterName must not be null"));
    }
}
