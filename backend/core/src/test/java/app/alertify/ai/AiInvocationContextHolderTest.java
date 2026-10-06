package app.alertify.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class AiInvocationContextHolderTest {

    @Test
    void restoresNestedContextAndClearsItAfterFailure() {
        AiInvocationContext outer = context(41L);
        AiInvocationContext inner = context(42L);

        assertThrows(IllegalStateException.class, () -> AiInvocationContextHolder.runWith(outer, () -> {
            assertSame(outer, AiInvocationContextHolder.current());
            AiInvocationContextHolder.runWith(inner, () -> assertSame(inner, AiInvocationContextHolder.current()));
            assertSame(outer, AiInvocationContextHolder.current());
            throw new IllegalStateException("expected");
        }));

        assertNull(AiInvocationContextHolder.current());
        assertFalse(AiInvocationContextHolder.currentProvenance().assisted());
    }

    @Test
    void wrappedWorkCarriesTheCapturedContextWithoutLeakingIt() {
        AiInvocationContext context = context(91L);
        AtomicReference<AiInvocationContext> observed = new AtomicReference<>();
        Runnable work = AiInvocationContextHolder.callWith(context,
                () -> AiInvocationContextHolder.wrap(() -> observed.set(AiInvocationContextHolder.current())));

        assertNull(AiInvocationContextHolder.current());
        work.run();

        assertSame(context, observed.get());
        assertNull(AiInvocationContextHolder.current());
        assertEquals(new AiProvenance(true, 91L), context.provenance());
    }

    private static AiInvocationContext context(long conversationId) {
        return new AiInvocationContext("subject", "username", Set.of("ROLE_ADMIN"), conversationId, UUID.randomUUID());
    }
}
