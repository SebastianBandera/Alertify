package app.alertify.alerts.api;

public record AlertBindingOptionResponse(
    Long id,
    String name,
    String description,
    boolean enabled
) {
    public AlertBindingOptionResponse(Long id, String name, String description) {
        this(id, name, description, true);
    }
}
