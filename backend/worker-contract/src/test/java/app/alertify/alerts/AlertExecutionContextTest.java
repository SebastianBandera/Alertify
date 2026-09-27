package app.alertify.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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

    @Test
    void exposesOnlyPreparedValuesWithoutAQueryableCatalog() {
        AlertExecutionContext context = new AlertExecutionContext("state", Map.of(), List.of(
                new AlertExecutionValue(AlertExecutionValueSource.CONFIGURATION, "Login User", "monitor"),
                new AlertExecutionValue(AlertExecutionValueSource.SECRET, "Login Password", "private")
        ));

        assertThat(context.requireValue(AlertExecutionValueSource.CONFIGURATION, "login user")).isEqualTo("monitor");
        assertThat(context.requireValue(AlertExecutionValueSource.SECRET, "LOGIN PASSWORD")).isEqualTo("private");
        assertThatThrownBy(() -> context.requireValue(AlertExecutionValueSource.SECRET, "unrelated"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was not prepared");
    }

    @Test
    void rejectsDuplicatePreparedValuesIgnoringNameCase() {
        assertThatThrownBy(() -> new AlertExecutionContext("", Map.of(), List.of(
                new AlertExecutionValue(AlertExecutionValueSource.SECRET, "TOKEN", "first"),
                new AlertExecutionValue(AlertExecutionValueSource.SECRET, "token", "second")
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate prepared value");
    }
}
