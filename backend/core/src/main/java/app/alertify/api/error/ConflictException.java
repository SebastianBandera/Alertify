package app.alertify.api.error;

import java.util.Map;

/**
 * Domain conflict carrying a stable error code and safe localization
 * parameters for the frontend.
 */
public class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final Map<String, String> parameters;

    public ConflictException(String message) {
        this("CONFLICT", message, Map.of());
    }

    public ConflictException(String code, String message, Map<String, String> parameters) {
        super(message);
        this.code = code;
        this.parameters = Map.copyOf(parameters);
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }
}
