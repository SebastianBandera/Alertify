package app.alertify.api.error;

/** Invalid selectors for one registered template; the diagnostic contains no supplied selectors. */
public class TemplateSelectionException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public TemplateSelectionException() {
        super("Provide a positive template ID or exact template key; both selectors must identify the same template");
    }
}
