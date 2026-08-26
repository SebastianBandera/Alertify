package app.alertify.alerts;

/**
 * Contract implemented by executable alert templates.
 */
@FunctionalInterface
public interface AlertEvaluator {

    AlertResult evaluate(AlertExecutionContext context) throws Exception;
}
