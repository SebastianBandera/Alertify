package app.alertify.ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    @ParameterizedTest
    @EnumSource(value = AiAlertParameterSource.class, names = "TEXT", mode = EnumSource.Mode.EXCLUDE)
    void requiresAPositiveIdForEverySelectedReferenceSource(AiAlertParameterSource source) {
        for (Long invalidId : Arrays.asList(null, 0L, -1L)) {
            var value = new AiAlertParameterValue("url", source, null, invalidId, invalidId, invalidId);
            assertThatThrownBy(value::toRequest)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be a positive ID for the selected source");
        }
    }

    @Test
    void permitsNullInactiveFieldsButStillRequiresSelectedText() {
        var value = new AiAlertParameterValue("url", AiAlertParameterSource.TEXT, "", null, null, null);
        assertThat(value.toRequest().textValue()).isEmpty();
        assertThat(value.toRequest().isSourceSelectionValid()).isTrue();
        var missingText = new AiAlertParameterValue("url", AiAlertParameterSource.TEXT, null, null, null, null);
        assertThatThrownBy(missingText::toRequest).hasMessage("textValue must be supplied when source is TEXT");
    }
}
