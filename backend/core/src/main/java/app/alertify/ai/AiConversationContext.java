package app.alertify.ai;

import java.util.function.Supplier;

/** Trusted entry point for a future chat layer to associate tool calls. */
public final class AiConversationContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private AiConversationContext() {
    }

    public static Long currentId() {
        return CURRENT.get();
    }

    public static <T> T callWith(Long conversationId, Supplier<T> action) {
        if (conversationId != null && conversationId <= 0)
            throw new IllegalArgumentException("AI conversation ID must be positive");

        Long previous = CURRENT.get();
        if (conversationId == null)
            CURRENT.remove();
        else
            CURRENT.set(conversationId);
        try {
            return action.get();
        } finally {
            if (previous == null)
                CURRENT.remove();
            else
                CURRENT.set(previous);
        }
    }
}
