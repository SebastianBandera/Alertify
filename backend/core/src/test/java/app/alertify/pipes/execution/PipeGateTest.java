package app.alertify.pipes.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class PipeGateTest {
    @Test
    void assignsWaitingHooksInFifoOrder() throws Exception {
        PipeGate gate = new PipeGate();
        assertThat(gate.tryEnter(false)).isTrue();
        List<Integer> acquired = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstWaiting = new CountDownLatch(1);
        CountDownLatch secondWaiting = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = executor.submit(() -> await(gate, firstWaiting, acquired, 1));
            assertThat(firstWaiting.await(1, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = executor.submit(() -> await(gate, secondWaiting, acquired, 2));
            assertThat(secondWaiting.await(1, TimeUnit.SECONDS)).isTrue();

            gate.leave();
            assertThat(first.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone()).isFalse();
            gate.leave();
            assertThat(second.get(1, TimeUnit.SECONDS)).isTrue();
            gate.leave();
        }

        assertThat(acquired).containsExactly(1, 2);
        assertThat(gate.isIdle()).isTrue();
    }

    @Test
    void removesTimedOutAndInterruptedWaiters() throws Exception {
        PipeGate gate = new PipeGate();
        assertThat(gate.tryEnter(false)).isTrue();
        assertThat(gate.awaitTurn(false, Duration.ofMillis(20), () -> { })).isFalse();

        CountDownLatch waiting = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> interrupted = executor.submit(() -> gate.awaitTurn(false, Duration.ofSeconds(5), waiting::countDown));
            assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
            interrupted.cancel(true);
        }

        gate.leave();
        assertThat(gate.isIdle()).isTrue();
    }

    private static boolean await(PipeGate gate, CountDownLatch waiting, List<Integer> acquired, int marker) throws InterruptedException {
        boolean result = gate.awaitTurn(false, Duration.ofSeconds(5), waiting::countDown);
        if (result)
            acquired.add(marker);

        return result;
    }
}
