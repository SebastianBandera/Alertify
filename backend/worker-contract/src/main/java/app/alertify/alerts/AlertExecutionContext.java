package app.alertify.alerts;

/**
 * Mutable, execution-local context through which an evaluator can publish a
 * compact diagnostic state. A fresh instance is used for every execution.
 */
public final class AlertExecutionContext {

    private String state;

    public AlertExecutionContext() {
        this(null);
    }

    public AlertExecutionContext(String state) {
        this.state = state == null ? "" : state;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state == null ? "" : state;
    }
}
