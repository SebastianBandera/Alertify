package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class WizardOperationDeadlineTest {
    @Test
    void sharesOneMonotonicBudgetAcrossCommandsAndNeverExceedsTwentyMinutes() {
        var nano = new AtomicLong();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
        var deadline = WizardOperationDeadline.fromEpochMillis(1000 + WizardOperationDeadline.MAX_MILLIS * 2, clock, nano::get);
        assertThat(deadline.remainingMillis()).isEqualTo(WizardOperationDeadline.MAX_MILLIS);
        nano.addAndGet(TimeUnit.MINUTES.toNanos(19));
        assertThat(deadline.capTimeout(TimeUnit.MINUTES.toMillis(5))).isEqualTo(60_000);
        nano.addAndGet(TimeUnit.MINUTES.toNanos(1));
        assertThat(deadline.expired()).isTrue();
        assertThatThrownBy(deadline::remainingMillis).isInstanceOf(WizardOperationDeadline.Expired.class);
    }

    @Test
    void inheritedDeadlineDoesNotRenewBetweenResetEnginesAndProcedures() {
        var nano = new AtomicLong();
        Clock start = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
        long epochDeadline = start.millis() + WizardOperationDeadline.MAX_MILLIS;
        var reset = WizardOperationDeadline.fromEpochMillis(epochDeadline, start, nano::get);
        nano.addAndGet(TimeUnit.MINUTES.toNanos(3));
        var replay = WizardOperationDeadline.fromEpochMillis(epochDeadline, Clock.offset(start, java.time.Duration.ofMinutes(3)), nano::get);
        assertThat(reset.remainingMillis()).isEqualTo(replay.remainingMillis()).isEqualTo(TimeUnit.MINUTES.toMillis(17));
        nano.addAndGet(TimeUnit.MINUTES.toNanos(16));
        assertThat(replay.capTimeout(TimeUnit.MINUTES.toMillis(20))).isEqualTo(60_000);
        assertThatThrownBy(() -> WizardOperationDeadline.fromEpochMillis(999L, start, nano::get).remainingMillis()).isInstanceOf(WizardOperationDeadline.Expired.class);
    }
}
