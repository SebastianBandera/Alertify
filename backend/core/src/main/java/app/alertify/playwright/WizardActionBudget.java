package app.alertify.playwright;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Message-scoped authority held only in the live session, never restored from draft JSON. */
final class WizardActionBudget {
    static final int MAX_REPLAYS = 20;
    static final Duration TURN_TIMEOUT = Duration.ofMinutes(60);
    static final Duration OPERATION_TIMEOUT = Duration.ofMinutes(20);
    private final String messageId = UUID.randomUUID().toString();
    private final Clock clock;
    private final Instant expiresAt;
    private int replaysUsed;

    WizardActionBudget() { this(Clock.systemUTC()); }
    WizardActionBudget(Clock clock) { this.clock = clock; expiresAt = clock.instant().plus(TURN_TIMEOUT); }

    String messageId() { return messageId; }
    Instant expiresAt() { return expiresAt; }

    synchronized void check(String expectedMessageId) {
        if (!messageId.equals(expectedMessageId))
            throw new IllegalArgumentException("The proposal belongs to an expired browser message. Read the script and submit a new request.");

        remainingMillis(expiresAt);
    }

    synchronized int replay() {
        requireReplay();
        return ++replaysUsed;
    }

    synchronized void requireReplay() {
        check(messageId);
        if (replaysUsed >= MAX_REPLAYS)
            throw new IllegalArgumentException("PLAYWRIGHT_REPLAY_LIMIT: this message reached 20 clean reproductions");
    }

    Instant operationDeadline() {
        check(messageId);
        Instant deadline = clock.instant().plus(OPERATION_TIMEOUT);
        return deadline.isBefore(expiresAt) ? deadline : expiresAt;
    }

    long remainingMillis(Instant deadline) {
        long remaining = Duration.between(clock.instant(), deadline).toMillis();
        if (remaining <= 0)
            throw new IllegalArgumentException(deadline.equals(expiresAt) ? "PLAYWRIGHT_CHAT_TIMEOUT: the browser message reached 60 minutes" : "PLAYWRIGHT_OPERATION_TIMEOUT: the browser operation reached its deadline");

        return remaining;
    }

    synchronized Map<String, Object> view() { return Map.of("messageId", messageId, "replaysUsed", replaysUsed, "maxReplays", MAX_REPLAYS, "expiresAt", expiresAt.toString()); }
}
