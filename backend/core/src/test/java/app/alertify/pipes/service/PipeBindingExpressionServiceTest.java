package app.alertify.pipes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.InvalidConfigurationExpressionException;
import app.alertify.configuration.service.ConfigurationExpressionParser;
import app.alertify.configuration.service.ConfigurationExpressionUtilityResolver;
import app.alertify.configuration.service.ExpressionEvaluator;
import tools.jackson.databind.json.JsonMapper;

class PipeBindingExpressionServiceTest {
    private final PipeBindingExpressionService service = new PipeBindingExpressionService(
            new ConfigurationExpressionParser(), new ConfigurationExpressionUtilityResolver());

    @Test
    void evaluatesPipeValueAndNestedUtilityFunctions() {
        String value = service.evaluate(
                "Basic {{utils.BASE64({{utils.BASE64_DECODE({{pipe.VALUE}})}})}}",
                "dXNlcjpwYXNz");

        assertThat(value).isEqualTo("Basic dXNlcjpwYXNz");
    }

    @Test
    void createsAJsonStringLiteralOnlyForPipeBindings() {
        String token = "opaque\"\\token";
        String expression = "[{{utils.JSON_STRING(Authorization: Bearer {{pipe.VALUE}})}}]";

        String value = service.evaluate(expression, token);

        assertThat(JsonMapper.builder().build().readTree(value).get(0).stringValue()).isEqualTo("Authorization: Bearer " + token);
        assertThat(service.functionNames()).contains("JSON_STRING");
        assertThatThrownBy(() -> new ConfigurationExpressionUtilityResolver().ensureSupported("JSON_STRING", true))
                .isInstanceOf(InvalidConfigurationExpressionException.class);
    }

    @Test
    void preservesDirectValuesWithoutEvaluatingTheirContents() {
        assertThat(service.evaluate(null, "{{secrets.PRIVATE}}")).isEqualTo("{{secrets.PRIVATE}}");
        assertThat(service.evaluate(" ", "{\"enabled\":true}")).isEqualTo("{\"enabled\":true}");
    }

    @Test
    void validatesTheRequiredPipeReferenceAndRejectsExternalScopes() {
        service.validate("[\"Authorization: Bearer {{pipe.VALUE}}\"]");

        assertThatThrownBy(() -> service.validate("literal"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("must reference");
        assertThatThrownBy(() -> service.validate("{{configs.TOKEN}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("pipe.VALUE");
        assertThatThrownBy(() -> service.validate("{{secrets.TOKEN}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class);
        assertThatThrownBy(() -> service.validate("{{env.TOKEN}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("pipe.VALUE");
        assertThatThrownBy(() -> service.validate("{{utils.YEAR}}-{{pipe.VALUE}}"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("only allow utility functions");
    }

    @Test
    void enforcesSyntaxNestingAndOutputLimits() {
        assertThatThrownBy(() -> service.validate("{{pipe.VALUE"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("not closed");

        String nested = "{{pipe.VALUE}}";
        for (int index = 0; index < 10; index++)
            nested = "{{utils.BASE64(" + nested + ")}}";

        String tooDeep = nested;
        assertThatThrownBy(() -> service.validate(tooDeep))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("nesting");
        assertThatThrownBy(() -> service.evaluate("{{pipe.VALUE}}", "x".repeat(ExpressionEvaluator.MAX_RESULT_BYTES + 1)))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessage("Pipe binding expression evaluation failed")
                .hasNoCause();
        assertThatThrownBy(() -> service.evaluate("{{utils.BASE64_DECODE({{pipe.VALUE}})}}", "sensitive-not-base64"))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessage("Pipe binding expression evaluation failed")
                .hasNoCause();
    }
}
