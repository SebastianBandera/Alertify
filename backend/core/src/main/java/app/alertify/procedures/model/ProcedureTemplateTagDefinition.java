package app.alertify.procedures.model;

import java.util.Locale;

import app.alertify.procedures.template.annotation.ProcedureTemplateTag;

/** Persistent representation of a code-owned procedure template tag. */
public record ProcedureTemplateTagDefinition(String nameKey, String color) {

    public static ProcedureTemplateTagDefinition from(ProcedureTemplateTag metadata) {
        return new ProcedureTemplateTagDefinition(
                metadata.nameKey(), metadata.color().isEmpty() ? null : metadata.color().toUpperCase(Locale.ROOT)
        );
    }
}
