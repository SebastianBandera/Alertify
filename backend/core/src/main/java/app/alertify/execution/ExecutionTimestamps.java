package app.alertify.execution;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Orders timestamps received from another process without changing its outcome. */
public record ExecutionTimestamps(Instant startedAt, Instant workStartedAt, Instant finishedAt) {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionTimestamps.class);

    public static ExecutionTimestamps ordered(String kind, UUID executionId, String workerName, Instant startedAt, Instant workStartedAt, Instant finishedAt) {
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(workStartedAt, "workStartedAt must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        Instant orderedWorkStartedAt = workStartedAt.isBefore(startedAt) ? startedAt : workStartedAt;
        Instant orderedFinishedAt = finishedAt.isBefore(orderedWorkStartedAt) ? orderedWorkStartedAt : finishedAt;
        if (!orderedWorkStartedAt.equals(workStartedAt) || !orderedFinishedAt.equals(finishedAt))
            LOGGER.warn("Execution timestamps corrected: kind={}, executionId={}, workerName={}, startedAt={}, workStartedAt={}, finishedAt={}, correctedWorkStartedAt={}, correctedFinishedAt={}", kind, executionId, workerName, startedAt, workStartedAt, finishedAt, orderedWorkStartedAt, orderedFinishedAt);

        return new ExecutionTimestamps(startedAt, orderedWorkStartedAt, orderedFinishedAt);
    }
}
