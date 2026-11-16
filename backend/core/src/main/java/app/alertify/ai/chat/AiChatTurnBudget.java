package app.alertify.ai.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** One authority for all provider requests, including review calls and upgraded streams. */
final class AiChatTurnBudget {
    static final int MAX_REQUESTS = 512;
    static final Duration TIMEOUT = Duration.ofMinutes(60);
    private final Clock clock;
    private final Instant deadline;
    private final Set<String> requests = new HashSet<>();
    private int consumed;

    AiChatTurnBudget() { this(Clock.systemUTC()); }
    AiChatTurnBudget(Clock clock) { this.clock = clock; deadline = clock.instant().plus(TIMEOUT); }

    synchronized String reserve(String requestId) {
        String failure = failure();
        if (!failure.isEmpty()) return failure;

        if (requestId == null || requestId.isBlank() || !requests.add(requestId)) return "AI_PROTOCOL_INVALID";

        consumed++;
        return "";
    }

    synchronized String failure() {
        if (!clock.instant().isBefore(deadline)) return "AI_TURN_TIMEOUT";
        if (consumed >= MAX_REQUESTS) return "AI_STEP_LIMIT";

        return "";
    }

    synchronized boolean expired() { return !clock.instant().isBefore(deadline); }
    synchronized int remaining() { return Math.max(0, MAX_REQUESTS - consumed); }
    Instant deadline() { return deadline; }
}
