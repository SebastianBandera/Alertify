package app.alertify.ai;

import java.util.function.Supplier;

/** Trusted entry point for a future chat layer to associate tool calls. */
public final class AiConversationContext {

    private static final String DEFAULT_LOCALE = "en";
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private AiConversationContext() {
    }

    public static Long currentId() {
        State state = CURRENT.get();
        return state == null ? null : state.conversationId();
    }

    public static String currentLocale() {
        State state = CURRENT.get();
        return state == null ? DEFAULT_LOCALE : state.locale();
    }

    public static <T> T callWith(Long conversationId, Supplier<T> action) {
        return callWith(conversationId, DEFAULT_LOCALE, action);
    }

    public static <T> T callWith(Long conversationId, String locale, Supplier<T> action) {
        if (conversationId != null && conversationId <= 0)
            throw new IllegalArgumentException("AI conversation ID must be positive");
        if (locale == null || locale.isBlank())
            throw new IllegalArgumentException("AI conversation locale must not be blank");

        State previous = CURRENT.get();
        CURRENT.set(new State(conversationId, locale));
        try {
            return action.get();
        } finally {
            if (previous == null)
                CURRENT.remove();
            else
                CURRENT.set(previous);
        }
    }

    private record State(Long conversationId, String locale) {
    }
}
