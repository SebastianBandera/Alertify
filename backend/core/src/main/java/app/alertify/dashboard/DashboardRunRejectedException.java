package app.alertify.dashboard;

import org.springframework.http.HttpStatus;

/**
 * A dashboard run refused before reaching the alert: the viewer's rate limit
 * was hit, or the limit could not be checked.
 */
public class DashboardRunRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    public DashboardRunRejectedException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
