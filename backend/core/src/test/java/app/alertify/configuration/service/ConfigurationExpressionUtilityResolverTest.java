package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.InvalidConfigurationExpressionException;

class ConfigurationExpressionUtilityResolverTest {

    private final ConfigurationExpressionUtilityResolver resolver = new ConfigurationExpressionUtilityResolver();

    @Test
    void resolvesDateAndTimeUtilitiesFromTheSameSnapshot() {
        ZonedDateTime now = ZonedDateTime.of(
                2026, 8, 5, 7, 4, 3, 456_000_000, ZoneId.of("America/Montevideo")
        );

        assertThat(resolver.resolve("YEAR", now)).isEqualTo("2026");
        assertThat(resolver.resolve("MONTH", now)).isEqualTo("8");
        assertThat(resolver.resolve("MONTH_PADDED", now)).isEqualTo("08");
        assertThat(resolver.resolve("DAY", now)).isEqualTo("5");
        assertThat(resolver.resolve("DAY_PADDED", now)).isEqualTo("05");
        assertThat(resolver.resolve("HOUR", now)).isEqualTo("7");
        assertThat(resolver.resolve("HOUR_PADDED", now)).isEqualTo("07");
        assertThat(resolver.resolve("MINUTE", now)).isEqualTo("4");
        assertThat(resolver.resolve("MINUTE_PADDED", now)).isEqualTo("04");
        assertThat(resolver.resolve("SECOND", now)).isEqualTo("3");
        assertThat(resolver.resolve("SECOND_PADDED", now)).isEqualTo("03");
        assertThat(resolver.resolve("DATE", now)).isEqualTo("2026-08-05");
        assertThat(resolver.resolve("TIME", now)).isEqualTo("07:04:03");
        assertThat(resolver.resolve("DATE_TIME", now)).isEqualTo("2026-08-05T07:04:03");
        assertThat(resolver.resolve("OFFSET_DATE_TIME", now)).isEqualTo("2026-08-05T07:04:03-03:00");
        assertThat(resolver.resolve("DAY_OF_WEEK", now)).isEqualTo("3");
        assertThat(resolver.resolve("DAY_OF_YEAR", now)).isEqualTo("217");
        assertThat(resolver.resolve("WEEK_OF_YEAR", now)).isEqualTo("32");
        assertThat(resolver.resolve("WEEK_YEAR", now)).isEqualTo("2026");
        assertThat(resolver.resolve("EPOCH_SECONDS", now)).isEqualTo("1785924243");
        assertThat(resolver.resolve("EPOCH_MILLIS", now)).isEqualTo("1785924243456");
        assertThat(resolver.resolve("TIME_ZONE", now)).isEqualTo("America/Montevideo");
        assertThat(resolver.resolve("UTC_OFFSET", now)).isEqualTo("-03:00");
    }

    @Test
    void rejectsUnknownUtility() {
        assertThatThrownBy(() -> resolver.resolve("UNKNOWN", ZonedDateTime.now()))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("Unsupported");
    }
}
