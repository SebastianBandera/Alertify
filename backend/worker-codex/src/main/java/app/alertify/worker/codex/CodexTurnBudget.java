package app.alertify.worker.codex;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** One exchange's ceiling; the backend additionally reserves the shared turn budget across upgrades and reviews. */
final class CodexTurnBudget {
    static final int GENERAL_REQUESTS = 512;
    static final int BROWSER_REQUESTS = 128;
    static final int BLOCK_REQUESTS = 64;
    static final Duration TURN_TIMEOUT = Duration.ofMinutes(60);
    private final Clock clock;
    private final Instant deadline;
    private final int limit;
    private int used;

    CodexTurnBudget(Clock clock, boolean browser, long deadlineEpochMillis, int remainingRequests, boolean sharedBudget) {
        this.clock = clock;
        Instant localDeadline = clock.instant().plus(TURN_TIMEOUT);
        Instant sharedDeadline = deadlineEpochMillis > 0 ? Instant.ofEpochMilli(deadlineEpochMillis) : localDeadline;
        deadline = sharedDeadline.isBefore(localDeadline) ? sharedDeadline : localDeadline;
        int ceiling = browser ? BROWSER_REQUESTS : GENERAL_REQUESTS;
        limit = sharedBudget ? Math.max(0, Math.min(remainingRequests, ceiling)) : ceiling;
    }

    synchronized void reserve() {
        remainingMillis();
        if (used >= limit)
            throw new Limit("AI_STEP_LIMIT", "The chat turn reached its model request budget; pending work remains saved");

        used++;
    }

    long remainingMillis() {
        long remaining = Duration.between(clock.instant(), deadline).toMillis();
        if (remaining <= 0)
            throw new Limit("AI_TURN_TIMEOUT", "The chat turn reached 60 minutes; pending work remains saved");

        return remaining;
    }

    static final class Limit extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String code;

        Limit(String code, String message) { super(message); this.code = code; }
        String code() { return code; }
    }
}
