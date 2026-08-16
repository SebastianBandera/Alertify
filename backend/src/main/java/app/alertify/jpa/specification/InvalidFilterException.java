package app.alertify.jpa.specification;

public class InvalidFilterException extends RuntimeException {
    public InvalidFilterException(String field) {
        super("Invalid filter: " + field);
    }

    public InvalidFilterException(String field, Throwable cause) {
        super("Invalid filter: " + field, cause);
    }
}
