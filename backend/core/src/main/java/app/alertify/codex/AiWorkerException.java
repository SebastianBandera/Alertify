package app.alertify.codex;

public class AiWorkerException extends RuntimeException {

    private final String code;

    public AiWorkerException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
