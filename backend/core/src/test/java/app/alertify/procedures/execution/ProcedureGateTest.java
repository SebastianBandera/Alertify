package app.alertify.procedures.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ProcedureGateTest {

    @Test
    void rejectsImmediateNonConcurrentExecutionButAllowsConcurrentExecution() {
        ProcedureGate gate = new ProcedureGate();
        assertTrue(gate.tryEnter(false));
        assertFalse(gate.tryEnter(false));
        assertTrue(gate.tryEnter(true));
        gate.leave();
        gate.leave();
        assertTrue(gate.isIdle());
    }

    @Test
    void assignsWaitingHooksInFifoOrderWithoutAllowingAnImmediateExecutionToJumpTheQueue() throws Exception {
        ProcedureGate gate = new ProcedureGate();
        assertTrue(gate.tryEnter(false));
        CountDownLatch bothWaiting = new CountDownLatch(2);
        CountDownLatch completed = new CountDownLatch(2);
        List<Integer> order = new CopyOnWriteArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> await(gate, 1, bothWaiting, completed, order));
            assertTrue(waitForCount(bothWaiting, 1));
            executor.submit(() -> await(gate, 2, bothWaiting, completed, order));
            assertTrue(bothWaiting.await(2, TimeUnit.SECONDS));

            gate.leave();
            assertFalse(gate.tryEnter(false));
            assertTrue(completed.await(2, TimeUnit.SECONDS));
        }

        assertTrue(order.equals(List.of(1, 2)));
        assertTrue(gate.isIdle());
    }

    @Test
    void removesTimedOutWaiterAndLeavesGateUsable() throws Exception {
        ProcedureGate gate = new ProcedureGate();
        assertTrue(gate.tryEnter(false));
        assertFalse(gate.awaitTurn(false, Duration.ofMillis(5), () -> { }));
        gate.leave();
        assertTrue(gate.isIdle());
        assertTrue(gate.tryEnter(false));
        gate.leave();
    }

    private static void await(ProcedureGate gate, int value, CountDownLatch waiting, CountDownLatch completed, List<Integer> order) {
        try {
            boolean acquired = gate.awaitTurn(false, Duration.ofSeconds(2), waiting::countDown);
            if (acquired) {
                order.add(value);
                gate.leave();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            completed.countDown();
        }
    }

    private static boolean waitForCount(CountDownLatch latch, long count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (latch.getCount() > count && System.nanoTime() < deadline)
            Thread.sleep(1);

        return latch.getCount() == count;
    }
}
