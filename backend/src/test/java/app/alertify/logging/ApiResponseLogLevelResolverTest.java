package app.alertify.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseLogLevelResolverTest {

    @Test
    void expectedBusinessConflictIsInfo() {
        assertThat(ApiResponseLogLevelResolver.resolve(409, "CONFIGURATION_TAG_IN_USE"))
            .isEqualTo(ApplicationLogLevel.INFO);
    }

    @Test
    void unexpectedClientErrorRemainsWarn() {
        assertThat(ApiResponseLogLevelResolver.resolve(409, "CONFLICT"))
            .isEqualTo(ApplicationLogLevel.WARN);
    }

    @Test
    void serverErrorRemainsError() {
        assertThat(ApiResponseLogLevelResolver.resolve(500, "CONFIGURATION_TAG_IN_USE"))
            .isEqualTo(ApplicationLogLevel.ERROR);
    }
}
