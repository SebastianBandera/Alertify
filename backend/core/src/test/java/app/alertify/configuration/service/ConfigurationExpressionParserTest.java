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
    void rejectsInvalidUtilityName() {
        assertThatThrownBy(() -> parser.parse("{{utils.year}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("Invalid utility");
    }

    @Test
    void parsesUtilityFunctionsWithNestedArguments() {
        var parsed = parser.parse(
                "Basic {{utils.BASE64({{configs.USER}}:{{env.PASSWORD}})}} {{utils.YEAR}}"
        );

        assertThat(parsed.references()).hasSize(2);
        var function = parsed.references().get(0);
        assertThat(function.type()).isEqualTo(ConfigurationExpressionParser.ReferenceType.UTILITY);
        assertThat(function.name()).isEqualTo("BASE64");
        assertThat(function.isFunction()).isTrue();
        assertThat(function.argument().source()).isEqualTo("{{configs.USER}}:{{env.PASSWORD}}");
        assertThat(function.argument().references()).hasSize(2);
        assertThat(parsed.configurationNames()).containsExactly("USER");
        assertThat(parsed.environmentNames()).containsExactly("PASSWORD");
        assertThat(parsed.utilityNames()).containsExactly("YEAR");
        assertThat(parsed.utilityFunctionNames()).containsExactly("BASE64");
        assertThat(parsed.source().substring(function.start(), function.end()))
                .isEqualTo("{{utils.BASE64({{configs.USER}}:{{env.PASSWORD}})}}");
    }

    @Test
    void acceptsLiteralFunctionArgumentsAndRejectsNestingElsewhere() {
        var parsed = parser.parse("{{utils.BASE64(abc)}}");
        assertThat(parsed.references().get(0).argument().references()).isEmpty();

        assertThatThrownBy(() -> parser.parse("{{configs.{{configs.A}}}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("Nested");
        assertThatThrownBy(() -> parser.parse("{{utils.BASE64(}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("Invalid utility");
        assertThatThrownBy(() -> parser.parse("{{utils.BASE64({{configs.A}})"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("not closed");
    }

    @Test
    void allowsSecretReferencesOnlyInSecretScope() {
        var parsed = parser.parse(
                "{{secrets.API_USER}}:{{configs.REALM}}", ConfigurationExpressionParser.ExpressionScope.SECRET
        );

        assertThat(parsed.secretNames()).containsExactly("API_USER");
        assertThat(parsed.configurationNames()).containsExactly("REALM");
        assertThatThrownBy(() -> parser.parse("{{secrets.API_USER}}", ConfigurationExpressionParser.ExpressionScope.CONFIGURATION))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("secrets cannot be referenced from configuration expressions");
        assertThatThrownBy(() -> parser.parse("{{secrets. bad}}", ConfigurationExpressionParser.ExpressionScope.SECRET))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("Invalid secret reference");
    }

    @Test
    void rejectsUnsupportedReferenceScope() {
        assertThatThrownBy(() -> parser.parse("{{secrets.API_TOKEN}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("Unsupported");
    }
}
