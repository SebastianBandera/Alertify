package app.alertify.playwright;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One browser-operation budget, retained across engines, commands and procedure calls. */
public final class WizardOperationDeadline {
    public static final long MAX_MILLIS = TimeUnit.MINUTES.toMillis(20);
    private final LongSupplier nanoTime;
    private final long deadline;

    private WizardOperationDeadline(long remainingMillis, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        deadline = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(remainingMillis);
    }

    public static WizardOperationDeadline fromEpochMillis(Long epochMillis) {
        return fromEpochMillis(epochMillis, Clock.systemUTC(), System::nanoTime);
    }

    public static WizardOperationDeadline fromEpochMillis(Long epochMillis, Clock clock, LongSupplier nanoTime) {
        long remaining = MAX_MILLIS;
        if (epochMillis != null) {
            try { remaining = Math.subtractExact(epochMillis, clock.millis()); }
            catch (ArithmeticException invalid) { throw new IllegalArgumentException("Invalid browser operation deadline"); }
        }
        return new WizardOperationDeadline(Math.clamp(remaining, 0, MAX_MILLIS), nanoTime);
    }

    public boolean expired() { return deadline - nanoTime.getAsLong() <= 0; }

    public long remainingMillis() {
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0)
            throw new Expired();

        return Math.max(1, (remaining + 999_999) / 1_000_000);
    }

    public long capTimeout(long configuredMillis) { return Math.min(configuredMillis, remainingMillis()); }

    public static final class Expired extends IllegalArgumentException {
        public Expired() { super("PLAYWRIGHT_OPERATION_TIMEOUT: the browser operation deadline was reached"); }
    }
}
