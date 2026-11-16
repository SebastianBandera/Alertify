package app.alertify.worker.codex;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class CodexTurnBudgetTest {
    @Test
    void requestBlocksNeverResetThe512RequestCeiling() {
        var budget = new CodexTurnBudget(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), false, 0, 0, false);
        for (int request = 0; request < 512; request++)
            budget.reserve();

        assertThatThrownBy(budget::reserve).isInstanceOf(CodexTurnBudget.Limit.class).extracting("code").isEqualTo("AI_STEP_LIMIT");
    }

    @Test
    void aReasoningUpgradeCannotRestoreConsumedSharedRequests() {
        var budget = new CodexTurnBudget(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), false, 0, 1, true);
        budget.reserve();
        assertThatThrownBy(budget::reserve).isInstanceOf(CodexTurnBudget.Limit.class).extracting("code").isEqualTo("AI_STEP_LIMIT");
        var exhausted = new CodexTurnBudget(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), false, 0, 0, true);
        assertThatThrownBy(exhausted::reserve).isInstanceOf(CodexTurnBudget.Limit.class);
    }

    @Test
    void expiryAt60MinutesUsesTheSharedOriginalDeadline() {
        var clock = new MutableClock();
        var budget = new CodexTurnBudget(clock, false, 0, 0, false);
        clock.now = Instant.EPOCH.plusSeconds(3599);
        budget.reserve();
        clock.now = Instant.EPOCH.plusSeconds(3600);
        assertThatThrownBy(budget::remainingMillis).isInstanceOf(CodexTurnBudget.Limit.class).extracting("code").isEqualTo("AI_TURN_TIMEOUT");
        var upgraded = new CodexTurnBudget(clock, false, Instant.EPOCH.plusSeconds(3600).toEpochMilli(), 400, true);
        assertThatThrownBy(upgraded::reserve).isInstanceOf(CodexTurnBudget.Limit.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
