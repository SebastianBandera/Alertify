package app.alertify.alerts.model;

import java.util.Locale;

import app.alertify.alerts.template.annotation.AlertTemplateTag;

/**
 * Persistent representation of a code-owned alert template tag.
 */
public record AlertTemplateTagDefinition(
    String nameKey,
    String color
) {

    public static AlertTemplateTagDefinition from(AlertTemplateTag metadata) {
        return new AlertTemplateTagDefinition(
            metadata.nameKey(), metadata.color().isEmpty() ? null : metadata.color().toUpperCase(Locale.ROOT)
        );
    }
}
