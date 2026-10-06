package app.alertify.ai;

import java.util.function.Supplier;

/**
 * Binds trusted AI metadata only for the current synchronous call. Async work
 * must capture the immutable context explicitly with {@link #wrap(Runnable)}.
 */
public final class AiInvocationContextHolder {

    private static final ThreadLocal<AiInvocationContext> CURRENT = new ThreadLocal<>();

    private AiInvocationContextHolder() {
    }

    public static AiInvocationContext current() {
        return CURRENT.get();
    }

    public static AiProvenance currentProvenance() {
        AiInvocationContext context = current();
        return context == null ? AiProvenance.NONE : context.provenance();
    }

    public static <T> T callWith(AiInvocationContext context, Supplier<T> action) {
        AiInvocationContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    public static void runWith(AiInvocationContext context, Runnable action) {
        callWith(context, () -> {
            action.run();
            return null;
        });
    }

    public static Runnable wrap(Runnable action) {
        AiInvocationContext captured = current();
        if (captured == null)
            return action;

        return () -> runWith(captured, action);
    }

    private static void restore(AiInvocationContext previous) {
        if (previous == null)
            CURRENT.remove();
        else
            CURRENT.set(previous);
    }
}
