package app.alertify.procedures.execution;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/** Coordinates active executions and FIFO Hook waiters for one Procedure. */
final class ProcedureGate {
    private int active;
    private final Deque<Waiter> waiters = new ArrayDeque<>();

    synchronized boolean tryEnter(boolean concurrent) {
        if (!concurrent && (active > 0 || !waiters.isEmpty()))
            return false;

        active++;
        return true;
    }

    boolean awaitTurn(boolean concurrent, Duration timeout, Runnable waitingCallback) throws InterruptedException {
        Waiter waiter;
        synchronized (this) {
            if (concurrent || (active == 0 && waiters.isEmpty())) {
                active++;
                return true;
            }

            waiter = new Waiter();
            waiters.addLast(waiter);
        }

        runCallback(waitingCallback);
        synchronized (this) {
            long remaining = timeout.toNanos();
            long started = System.nanoTime();
            try {
                while (!waiter.assigned && remaining > 0) {
                    long millis = Math.max(1, Math.min(Duration.ofNanos(remaining).toMillis(), Integer.MAX_VALUE));
                    wait(millis);
                    remaining = timeout.toNanos() - (System.nanoTime() - started);
                }
            } catch (InterruptedException exception) {
                if (waiter.assigned) {
                    Thread.currentThread().interrupt();
                    return true;
                }

                waiters.remove(waiter);
                throw exception;
            }
            if (waiter.assigned)
                return true;

            waiters.remove(waiter);
            return false;
        }
    }

    synchronized void leave() {
        active--;
        Waiter next = active == 0 ? waiters.pollFirst() : null;
        if (next != null) {
            active = 1;
            next.assigned = true;
            notifyAll();
        }
    }

    synchronized boolean isActive() { return active > 0; }
    synchronized boolean isIdle() { return active == 0 && waiters.isEmpty(); }

    private static void runCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Invocation state can be reconciled; callback failures must not leak a Procedure permit.
        }
    }

    private static final class Waiter { private boolean assigned; }
}
