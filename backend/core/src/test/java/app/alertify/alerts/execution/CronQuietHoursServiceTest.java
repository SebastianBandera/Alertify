package app.alertify.alerts.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.jpa.repository.SystemConfigurationRepository;
import app.alertify.logging.ApplicationEventLogger;

@ExtendWith(MockitoExtension.class)
class CronQuietHoursServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId ZONE = ZoneId.of("UTC");

    @Mock private SystemConfigurationRepository repository;
    @Mock private ApplicationEventLogger eventLogger;

    private CronQuietHoursService service;

    @BeforeEach
    void setUp() {
        service = new CronQuietHoursService(repository, eventLogger);
    }

    // -- isWithin (pure range logic, no clock/repository involved) --

    @Test
    void nonWrappingRangeIsInclusiveOfStartAndExclusiveOfEnd() {
        LocalTime start = LocalTime.of(10, 0);
        LocalTime end = LocalTime.of(14, 0);

        assertThat(CronQuietHoursService.isWithin(LocalTime.of(9, 59), start, end)).isFalse();
        assertThat(CronQuietHoursService.isWithin(LocalTime.of(10, 0), start, end)).isTrue();
        assertThat(CronQuietHoursService.isWithin(LocalTime.of(12, 0), start, end)).isTrue();
        assertThat(CronQuietHoursService.isWithin(LocalTime.of(14, 0), start, end)).isFalse();
    }

    @Test
    void wrappingRangeSpansMidnight() {
        LocalTime start = LocalTime.of(23, 0);
        LocalTime end = LocalTime.of(7, 0);

        assertThat(CronQuietHoursService.isWithin(LocalTime.of(22, 59), start, end)).isFalse();
        assertThat(CronQuietHoursService.isWithin(LocalTime.of(23, 0), start, end)).isTrue();
        assertThat(CronQuietHoursService.isWithin(LocalTime.of(3, 0), start, end)).isTrue();
        assertThat(CronQuietHoursService.isWithin(LocalTime.of(6, 59), start, end)).isTrue();
        assertThat(CronQuietHoursService.isWithin(LocalTime.of(7, 0), start, end)).isFalse();
        assertThat(CronQuietHoursService.isWithin(LocalTime.of(12, 0), start, end)).isFalse();
    }

    @Test
    void equalStartAndEndIsNeverQuiet() {
        LocalTime time = LocalTime.of(10, 0);

        assertThat(CronQuietHoursService.isWithin(time, time, time)).isFalse();
    }

    // -- isQuietNow (fail-open behavior) --

    @Test
    void failsOpenWhenConfigurationIsMissing() {
        when(repository.findByNameIgnoreCase("CRON_QUIET_HOURS")).thenReturn(Optional.empty());

        assertThat(service.isQuietNow()).isFalse();
    }

    @Test
    void failsOpenWhenDisabled() {
        withConfiguration("{\"enabled\": false, \"start\": \"23:00\", \"end\": \"07:00\"}");
        service.setClockForTesting(clockAt("23:30"));

        assertThat(service.isQuietNow()).isFalse();
    }

    @Test
    void failsOpenWhenValueIsMalformed() {
        withConfiguration("{\"enabled\": true, \"start\": \"not-a-time\", \"end\": \"07:00\"}");

        assertThat(service.isQuietNow()).isFalse();
    }

    @Test
    void failsOpenWhenFieldsAreMissing() {
        withConfiguration("{\"enabled\": true}");

        assertThat(service.isQuietNow()).isFalse();
    }

    @Test
    void reportsQuietWhenEnabledAndWithinRange() {
        withConfiguration("{\"enabled\": true, \"start\": \"23:00\", \"end\": \"07:00\"}");
        service.setClockForTesting(clockAt("23:30"));

        assertThat(service.isQuietNow()).isTrue();
    }

    // -- checkForTransition (logs once per transition, not on every tick) --

    @Test
    void firstCheckInitializesStateWithoutLogging() {
        withConfiguration("{\"enabled\": true, \"start\": \"23:00\", \"end\": \"07:00\"}");
        service.setClockForTesting(clockAt("23:30"));

        service.checkForTransition();

        verify(eventLogger, never()).success(any(), any());
    }

    @Test
    void logsOnceWhenTransitioningIntoQuietPeriod() {
        withConfiguration("{\"enabled\": false, \"start\": \"23:00\", \"end\": \"07:00\"}");
        service.setClockForTesting(clockAt("23:30"));
        service.checkForTransition();

        withConfiguration("{\"enabled\": true, \"start\": \"23:00\", \"end\": \"07:00\"}");
        service.checkForTransition();
        service.checkForTransition();

        verify(eventLogger).success(eq("CRON_QUIET_PERIOD_STARTED"), any());
        verify(eventLogger, never()).success(eq("CRON_QUIET_PERIOD_ENDED"), any());
    }

    @Test
    void logsOnceWhenTransitioningOutOfQuietPeriod() {
        withConfiguration("{\"enabled\": true, \"start\": \"23:00\", \"end\": \"07:00\"}");
        service.setClockForTesting(clockAt("23:30"));
        service.checkForTransition();

        withConfiguration("{\"enabled\": false, \"start\": \"23:00\", \"end\": \"07:00\"}");
        service.checkForTransition();
        service.checkForTransition();

        verify(eventLogger).success(eq("CRON_QUIET_PERIOD_ENDED"), any());
        verify(eventLogger, never()).success(eq("CRON_QUIET_PERIOD_STARTED"), any());
    }

    private void withConfiguration(String json) {
        JsonNode value = JSON.readTree(json);
        when(repository.findByNameIgnoreCase("CRON_QUIET_HOURS"))
                .thenReturn(Optional.of(new SystemConfiguration("CRON_QUIET_HOURS", null, value, false)));
    }

    private static Clock clockAt(String time) {
        LocalTime localTime = LocalTime.parse(time);
        Instant instant = localTime.atDate(java.time.LocalDate.of(2026, 1, 1)).atZone(ZONE).toInstant();
        return Clock.fixed(instant, ZONE);
    }
}
