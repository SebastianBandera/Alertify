package app.alertify.alerts.api;

public record AlertBindingOptionResponse(
    Long id,
    String name,
    String description,
    boolean enabled,
    String valueType,
    boolean writable
) {
    public AlertBindingOptionResponse(Long id, String name, String description) {
        this(id, name, description, true, null, false);
    }

    public AlertBindingOptionResponse(Long id, String name, String description, boolean enabled) {
        this(id, name, description, enabled, null, false);
    }
}
