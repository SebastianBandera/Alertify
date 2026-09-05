package app.alertify.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.template.annotation.AlertParameterSource;

class AlertExecutionContextTest {

    @Test
    void exposesTheSourceOfEachParameterWithoutSecretTargetMetadata() {
        AlertExecutionContext context = new AlertExecutionContext("state", Map.of(
                "endpoint", AlertParameterSource.TEXT,
                "timeout", AlertParameterSource.CONFIGURATION,
                "token", AlertParameterSource.SECRET
        ));

        assertThat(context.getParameterSource("endpoint")).isEqualTo(AlertParameterSource.TEXT);
        assertThat(context.getParameterSource("timeout")).isEqualTo(AlertParameterSource.CONFIGURATION);
        assertThat(context.getParameterSource("token")).isEqualTo(AlertParameterSource.SECRET);
        assertThat(context.getParameterSource("unknown")).isNull();
    }
}
