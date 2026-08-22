package app.alertify.jpa.specification;

public class InvalidFilterException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidFilterException(String field) {
        super("Invalid filter: " + field);
    }

    public InvalidFilterException(String field, Throwable cause) {
        super("Invalid filter: " + field, cause);
    }
}
