package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ExecutionTimelineTest {

    @Test
    void keepsElapsedTimesOrderedWhenTheCounterMovesBackwards() {
        Instant startedAt = Instant.parse("2026-09-25T15:00:00Z");
        AtomicLong counter = new AtomicLong(1_000_000);
        ExecutionTimeline timeline = new ExecutionTimeline("execution-1", startedAt, counter.get(), counter::get);

        counter.set(999_999);
        assertThat(timeline.next()).isEqualTo(startedAt);
        counter.set(1_004_000);
        assertThat(timeline.next()).isEqualTo(startedAt.plusNanos(4_000));
        counter.set(1_003_000);
        assertThat(timeline.next()).isEqualTo(startedAt.plusNanos(4_000));
        counter.set(1_011_000);
        assertThat(timeline.next()).isEqualTo(startedAt.plusNanos(11_000));
    }
}
