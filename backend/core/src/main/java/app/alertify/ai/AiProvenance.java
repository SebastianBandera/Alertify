package app.alertify.ai;

/** Persistable AI origin, independent of any future conversation entity. */
public record AiProvenance(boolean assisted, Long conversationId) {

    public static final AiProvenance NONE = new AiProvenance(false, null);

    public AiProvenance {
        if (conversationId != null && conversationId <= 0)
            throw new IllegalArgumentException("AI conversation ID must be positive");

        if (conversationId != null && !assisted)
            throw new IllegalArgumentException("An AI conversation requires assisted=true");
    }
}
