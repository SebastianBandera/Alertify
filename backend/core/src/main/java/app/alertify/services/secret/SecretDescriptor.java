package app.alertify.services.secret;

/**
 * Internal metadata-only description of an available secret for consumers
 * that must select a name without reading its value.
 */
public record SecretDescriptor(
    String name,
    String description
) {
}
