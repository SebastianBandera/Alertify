package app.alertify.ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.template.annotation.AlertParameterSource;

class AiAlertParameterValueTest {

    @Test
    void ignoresStrictSchemaPlaceholdersForAnUnselectedReferenceSource() {
        var value = new AiAlertParameterValue("url", AiAlertParameterSource.TEXT, "https://example.test", 0L, 0L, 0L);

        var request = value.toRequest();

        assertThat(request.source()).isEqualTo(AlertParameterSource.TEXT);
        assertThat(request.textValue()).isEqualTo("https://example.test");
        assertThat(request.configurationId()).isNull();
        assertThat(request.secretId()).isNull();
        assertThat(request.procedureId()).isNull();
        assertThat(request.isSourceSelectionValid()).isTrue();
    }

    @Test
    void requiresAPositiveIdForTheSelectedReferenceSource() {
        var value = new AiAlertParameterValue("url", AiAlertParameterSource.CONFIGURATION, "", 0L, 0L, 0L);

        assertThatThrownBy(value::toRequest)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("configurationId must be a positive ID for the selected source");
    }
}
