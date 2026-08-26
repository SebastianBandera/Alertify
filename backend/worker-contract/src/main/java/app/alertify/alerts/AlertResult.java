package app.alertify.alerts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import app.alertify.alerts.execution.AlertExecutionStatus;

/**
 * Normal evaluator result. Exceptions are converted to ERROR executions by
 * the future worker execution layer and therefore cannot be constructed here.
 */
public record AlertResult(
    AlertExecutionStatus status,
    Map<String, Object> statusMessage
) {

    public AlertResult {
        Objects.requireNonNull(status, "status must not be null");
        if (status == AlertExecutionStatus.ERROR)
            throw new IllegalArgumentException("ERROR is reserved for exceptions");
        statusMessage = statusMessage == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(statusMessage));
    }

    public static AlertResult success(Map<String, Object> statusMessage) {
        return new AlertResult(AlertExecutionStatus.SUCCESS, statusMessage);
    }

    public static AlertResult warn(Map<String, Object> statusMessage) {
        return new AlertResult(AlertExecutionStatus.WARN, statusMessage);
    }
}
