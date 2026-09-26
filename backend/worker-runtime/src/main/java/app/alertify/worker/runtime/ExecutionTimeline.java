package app.alertify.worker.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keeps execution timestamps ordered even if a clock reading moves backwards. */
final class ExecutionTimeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionTimeline.class);

    private final String executionId;
    private final Instant startedAt;
    private final long startedNanos;
    private final LongSupplier nanoTime;
    private long lastElapsedNanos;

    static ExecutionTimeline start(String executionId) {
        Instant startedAt = Instant.now();
        return new ExecutionTimeline(executionId, startedAt, System.nanoTime(), System::nanoTime);
    }

    ExecutionTimeline(String executionId, Instant startedAt, long startedNanos, LongSupplier nanoTime) {
        this.executionId = Objects.requireNonNull(executionId);
        this.startedAt = Objects.requireNonNull(startedAt);
        this.startedNanos = startedNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    Instant startedAt() {
        return startedAt;
    }

    Instant next() {
        long elapsedNanos = nanoTime.getAsLong() - startedNanos;
        if (elapsedNanos < lastElapsedNanos) {
            LOGGER.warn("Execution monotonic clock moved backwards: executionId={}, elapsedNanos={}, previousElapsedNanos={}", executionId, elapsedNanos, lastElapsedNanos);
            elapsedNanos = lastElapsedNanos;
        }

        lastElapsedNanos = elapsedNanos;
        return startedAt.plusNanos(elapsedNanos);
    }
}
