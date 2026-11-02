package app.alertify.procedures.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import tools.jackson.databind.JsonNode;

class TotpProcedureTemplateTest {

    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void generatesTheRfc6238Sha1VectorAndItsValidityWindow() throws Exception {
        TotpProcedureTemplate procedure = new TotpProcedureTemplate(RFC_SECRET, "SHA1", 8, 30, 0);

        JsonNode result = procedure.execute(new ProcedureExecutionContext(
                Instant.ofEpochSecond(59), Map.of()
        ));

        assertThat(result.path("code").stringValue()).isEqualTo("94287082");
        assertThat(result.path("validFrom").stringValue()).isEqualTo("1970-01-01T00:00:30Z");
        assertThat(result.path("validUntil").stringValue()).isEqualTo("1970-01-01T00:01:00Z");
    }

    @Test
    void marksTheTemplateResultAsSensitive() {
        ProcedureTemplate metadata = TotpProcedureTemplate.class.getAnnotation(ProcedureTemplate.class);

        assertThat(metadata.sensitiveResult()).isTrue();
    }

    @Test
    void rejectsAnInvalidBase32Secret() {
        TotpProcedureTemplate procedure = new TotpProcedureTemplate("NOT_A_BASE32_SECRET!", "SHA1", 6, 30, 1);

        assertThatThrownBy(() -> procedure.execute(new ProcedureExecutionContext(Instant.EPOCH, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base32");
    }

    @Test
    void defaultsToThreeSeconds() throws Exception {
        ProcedureParameter parameter = TotpProcedureTemplate.class.getDeclaredField("minimumValiditySeconds")
                .getAnnotation(ProcedureParameter.class);

        assertThat(parameter.defaultValue()).isEqualTo("3");
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void waitsOnlyForTheRemainingFractionAndGeneratesTheNextPin(int margin) throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.ofEpochSecond(59, 700_000_000));
        List<Duration> waits = new ArrayList<>();
        TotpProcedureTemplate procedure = new TotpProcedureTemplate(RFC_SECRET, "SHA1", 8, 30, margin);

        JsonNode result = procedure.execute(context(), clock(now), duration -> {
            waits.add(duration);
            now.updateAndGet(instant -> instant.plus(duration));
        });

        assertThat(waits).containsExactly(Duration.ofMillis(300));
        assertThat(result).isEqualTo(new TotpProcedureTemplate(RFC_SECRET, "SHA1", 8, 30, 0)
                .execute(new ProcedureExecutionContext(Instant.ofEpochSecond(60), Map.of())));
        assertThat(result.path("validFrom").stringValue()).isEqualTo("1970-01-01T00:01:00Z");
        assertThat(result.path("validUntil").stringValue()).isEqualTo("1970-01-01T00:01:30Z");
    }

    @ParameterizedTest
    @ValueSource(longs = { 57, 58 })
    void doesNotWaitAtOrAboveTheMargin(long epochSecond) throws Exception {
        Instant now = Instant.ofEpochSecond(epochSecond);
        JsonNode result = new TotpProcedureTemplate(RFC_SECRET, "SHA1", 8, 30, 2)
                .execute(context(), Clock.fixed(now, ZoneOffset.UTC), duration -> {
                    throw new AssertionError("Unexpected wait");
                });

        assertThat(result.path("code").stringValue()).isEqualTo("94287082");
    }

    @Test
    void zeroUsesTheContextInstantWithoutWaiting() throws Exception {
        JsonNode result = new TotpProcedureTemplate(RFC_SECRET, "SHA1", 8, 30, 0)
                .execute(context(), Clock.fixed(Instant.ofEpochSecond(90), ZoneOffset.UTC), duration -> {
                    throw new AssertionError("Unexpected wait");
                });

        assertThat(result.path("validFrom").stringValue()).isEqualTo(Instant.EPOCH.toString());
    }

    @Test
    void reevaluatesTheClockAfterADelayedWakeup() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.ofEpochSecond(59, 500_000_000));
        List<Duration> waits = new ArrayList<>();
        JsonNode result = new TotpProcedureTemplate(RFC_SECRET, "SHA1", 8, 30, 2)
                .execute(context(), clock(now), duration -> {
                    waits.add(duration);
                    now.set(waits.size() == 1 ? Instant.ofEpochSecond(89, 750_000_000) : Instant.ofEpochSecond(90));
                });

        assertThat(waits).containsExactly(Duration.ofMillis(500), Duration.ofMillis(250));
        assertThat(result.path("validFrom").stringValue()).isEqualTo("1970-01-01T00:01:30Z");
    }

    @Test
    void propagatesInterruptionDuringTheWait() {
        assertThatThrownBy(() -> new TotpProcedureTemplate(RFC_SECRET, "SHA1", 8, 30, 2)
                .execute(context(), Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC), duration -> {
                    throw new InterruptedException("cancelled");
                })).isInstanceOf(InterruptedException.class);
    }

    @Test
    void validatesTheSecretBeforeWaiting() {
        assertThatThrownBy(() -> new TotpProcedureTemplate("!", "SHA1", 8, 30, 2)
                .execute(context(), Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC), duration -> {
                    throw new AssertionError("Unexpected wait");
                })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Base32");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = { -1, 30, 31 })
    void rejectsAnInvalidMargin(Integer margin) {
        assertThatThrownBy(() -> new TotpProcedureTemplate(RFC_SECRET, "SHA1", 8, 30, margin).execute(context()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minimumValiditySeconds");
    }

    private static ProcedureExecutionContext context() {
        return new ProcedureExecutionContext(Instant.EPOCH, Map.of());
    }

    private static Clock clock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }

            @Override
            public Clock withZone(java.time.ZoneId zone) { return this; }

            @Override
            public Instant instant() { return now.get(); }
        };
    }
}
