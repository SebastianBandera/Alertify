package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.InvalidConfigurationExpressionException;

class ConfigurationExpressionParserTest {

    private final ConfigurationExpressionParser parser = new ConfigurationExpressionParser();

    @Test
    void parsesConfigurationAndEnvironmentReferences() {
        var parsed = parser.parse(
                "{{configs.NAME1}}_{{configs.NAME2}}__{{env.ENVIRONMENT_VAR_NAME}}"
        );

        assertThat(parsed.configurationNames()).containsExactlyInAnyOrder("NAME1", "NAME2");
        assertThat(parsed.environmentNames()).containsExactly("ENVIRONMENT_VAR_NAME");
        assertThat(parsed.references()).hasSize(3);
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
    void rejectsUnsupportedReferenceScope() {
        assertThatThrownBy(() -> parser.parse("{{secrets.API_TOKEN}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("Unsupported");
    }
}
