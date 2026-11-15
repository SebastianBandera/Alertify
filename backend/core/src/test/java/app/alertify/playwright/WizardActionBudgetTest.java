package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WizardActionBudgetTest {
    @Test
    void twentyCleanReproductionsShareOneMessageBudgetIncludingAfterApproval() {
        var budget = new WizardActionBudget(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int attempt = 1; attempt <= 20; attempt++) {
            budget.check(budget.messageId()); // Approving the same message does not recreate its authority.
            assertThat(budget.replay()).isEqualTo(attempt);
        }
        assertThatThrownBy(budget::replay).hasMessageContaining("PLAYWRIGHT_REPLAY_LIMIT");
        var next = new WizardActionBudget(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        assertThat(next.replay()).isEqualTo(1);
        assertThatThrownBy(() -> next.check(budget.messageId())).hasMessageContaining("expired");
    }

    @Test
    void operationDeadlinesShareTwentyMinutesAndAreCappedByTheSixtyMinuteTurn() {
        var clock = new MutableClock();
        var budget = new WizardActionBudget(clock);
        Instant operation = budget.operationDeadline();
        clock.advance(Duration.ofMinutes(19));
        assertThat(budget.remainingMillis(operation)).isEqualTo(Duration.ofMinutes(1).toMillis());
        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> budget.remainingMillis(operation)).hasMessageContaining("PLAYWRIGHT_OPERATION_TIMEOUT");
        clock.advance(Duration.ofMinutes(35));
        assertThat(budget.operationDeadline()).isEqualTo(budget.expiresAt());
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> budget.check(budget.messageId())).hasMessageContaining("PLAYWRIGHT_CHAT_TIMEOUT");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
