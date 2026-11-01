package app.alertify.ai.patch;

/** Only fixed application messages and schema field names belong in these model-visible diagnostics. */
public class AiPatchException extends IllegalArgumentException {
    public AiPatchException() {
        this("PATCH result has an unknown field or incompatible value type");
    }

    public AiPatchException(String message) {
        super(message);
    }

    public static final class Verification extends IllegalStateException {
        public Verification() {
            super("PATCH persistence verification failed; the transaction was rolled back. No partial changes were committed.");
        }
    }
}
