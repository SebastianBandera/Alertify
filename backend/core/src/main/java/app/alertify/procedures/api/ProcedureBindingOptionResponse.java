package app.alertify.procedures.api;

public record ProcedureBindingOptionResponse(Long id, String name, String description, boolean enabled, String valueType) {
    public ProcedureBindingOptionResponse(Long id, String name, String description, boolean enabled) {
        this(id, name, description, enabled, null);
    }
}
