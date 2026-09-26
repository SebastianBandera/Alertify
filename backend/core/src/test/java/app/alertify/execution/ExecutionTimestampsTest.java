package app.alertify.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ExecutionTimestampsTest {

    @Test
    void correctsBothReversedBoundariesWithoutChangingAnOrderedResult() {
        Instant startedAt = Instant.parse("2026-09-25T15:00:00Z");
        UUID executionId = UUID.randomUUID();

        ExecutionTimestamps corrected = ExecutionTimestamps.ordered("ALERT", executionId, "worker-1",
                startedAt, startedAt.minusNanos(1), startedAt.minusNanos(2));
        assertThat(corrected.workStartedAt()).isEqualTo(startedAt);
        assertThat(corrected.finishedAt()).isEqualTo(startedAt);

        ExecutionTimestamps ordered = ExecutionTimestamps.ordered("ALERT", executionId, "worker-1",
                startedAt, startedAt.plusMillis(4), startedAt.plusMillis(11));
        assertThat(ordered.workStartedAt()).isEqualTo(startedAt.plusMillis(4));
        assertThat(ordered.finishedAt()).isEqualTo(startedAt.plusMillis(11));
    }
}
