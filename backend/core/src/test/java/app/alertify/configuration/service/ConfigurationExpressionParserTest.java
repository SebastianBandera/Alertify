package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.InvalidConfigurationExpressionException;

class ConfigurationExpressionParserTest {

    private final ConfigurationExpressionParser parser = new ConfigurationExpressionParser();

    @Test
    void parsesConfigurationEnvironmentAndUtilityReferences() {
        var parsed = parser.parse(
                "{{configs.NAME1}}_{{configs.NAME2}}__{{env.ENVIRONMENT_VAR_NAME}}__{{utils.YEAR}}"
        );

        assertThat(parsed.configurationNames()).containsExactlyInAnyOrder("NAME1", "NAME2");
        assertThat(parsed.environmentNames()).containsExactly("ENVIRONMENT_VAR_NAME");
        assertThat(parsed.utilityNames()).containsExactly("YEAR");
        assertThat(parsed.references()).hasSize(4);
    }

    @Test
    void rejectsUnclosedReference() {
        assertThatThrownBy(() -> parser.parse("prefix-{{configs.NAME"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("not closed");
    }

    @Test
    void rejectsKeyPartReference() {
        assertThatThrownBy(() -> parser.parse("{{configs.KEY_PART}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("cannot be referenced");
    }

    @Test
    void rejectsInvalidUtilityName() {
        assertThatThrownBy(() -> parser.parse("{{utils.year}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("Invalid utility");
    }

    @Test
    void rejectsUnsupportedReferenceScope() {
        assertThatThrownBy(() -> parser.parse("{{secrets.API_TOKEN}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("Unsupported");
    }
}
