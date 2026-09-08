package app.alertify.hooks;

import org.springframework.http.HttpStatus;

public class HookInvocationRejectedException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public HookInvocationRejectedException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
