package app.alertify.worker.runtime;

public class TemplateCompilationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    TemplateCompilationException(String message) {
        super(message);
    }

    TemplateCompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}
