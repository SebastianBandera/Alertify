package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.InvalidSecretValueException;
import app.alertify.configuration.service.ConfigurationExpressionParser;
import app.alertify.jpa.entity.SecretValueType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.StringNode;

class SecretValueValidatorTest {

    private final SecretValueValidator validator = new SecretValueValidator(new ConfigurationExpressionParser());
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void returnsStringValuesUnchanged() {
        assertThat(validator.validateAndNormalize(SecretValueType.STRING, StringNode.valueOf("  token \n")))
                .isEqualTo("  token \n");
        assertThat(validator.validateAndNormalizeRaw(SecretValueType.STRING, "raw")).isEqualTo("raw");
    }

    @Test
    void rejectsNonStringValuesForStringSecrets() {
        assertThatThrownBy(() -> validator.validateAndNormalize(SecretValueType.STRING, IntNode.valueOf(5)))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("STRING");
        assertThatThrownBy(() -> validator.validateAndNormalize(SecretValueType.STRING, null))
                .isInstanceOf(InvalidSecretValueException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize(null, StringNode.valueOf("x")))
                .isInstanceOf(InvalidSecretValueException.class);
    }

    @Test
    void normalizesDatabaseSecretsToCanonicalJson() {
        JsonNode value = json("{\"password\":\"p\",\"options\":\"\",\"username\":\" u \",\"database\":\"d\","
                + "\"port\":5432,\"host\":\" db.local \",\"engine\":\"POSTGRESQL\"}");

        String canonical = validator.validateAndNormalize(SecretValueType.DB_SECRET, value);

        assertThat(canonical).isEqualTo("{\"engine\":\"POSTGRESQL\",\"host\":\"db.local\",\"port\":5432,"
                + "\"database\":\"d\",\"username\":\"u\",\"password\":\"p\",\"options\":null}");
        assertThat(validator.validateAndNormalizeRaw(SecretValueType.DB_SECRET, canonical)).isEqualTo(canonical);
    }

    @Test
    void rejectsMalformedDatabaseSecrets() {
        assertThatThrownBy(() -> validator.validateAndNormalize(SecretValueType.DB_SECRET, StringNode.valueOf("host")))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("JSON object");
        assertThatThrownBy(() -> validator.validateAndNormalize(SecretValueType.DB_SECRET, json(
                "{\"engine\":\"POSTGRESQL\",\"host\":\"h\",\"port\":70000,\"database\":\"d\",\"username\":\"u\",\"password\":\"p\"}")))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> validator.validateAndNormalize(SecretValueType.DB_SECRET, json(
                "{\"engine\":\"POSTGRESQL\",\"host\":\"h\",\"port\":1,\"database\":\"d\",\"username\":\"u\"}")))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("password");
        assertThatThrownBy(() -> validator.validateAndNormalize(SecretValueType.DB_SECRET, json(
                "{\"engine\":\"ACCESS\",\"host\":\"h\",\"port\":1,\"database\":\"d\",\"username\":\"u\",\"password\":\"p\"}")))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("engine");
        assertThatThrownBy(() -> validator.validateAndNormalize(SecretValueType.DB_SECRET, json(
                "{\"engine\":\"POSTGRESQL\",\"host\":\"h\",\"port\":1,\"database\":\"d\",\"username\":\"u\",\"password\":\"p\",\"ssl\":true}")))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("ssl");
        assertThatThrownBy(() -> validator.validateAndNormalize(SecretValueType.DB_SECRET, json(
                "{\"engine\":\"OTHER\",\"host\":\"h\",\"port\":1,\"database\":\"d\",\"username\":\"u\",\"password\":\"p\"}")))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("jdbc:");
        assertThatThrownBy(() -> validator.validateAndNormalizeRaw(SecretValueType.DB_SECRET, "not json"))
                .isInstanceOf(InvalidSecretValueException.class);
    }

    @Test
    void validatesExpressionSyntaxWithSecretScope() {
        String expression = "Basic {{utils.BASE64({{secrets.USER}}:{{secrets.PASS}})}} {{configs.REALM}}";

        assertThat(validator.validateAndNormalize(SecretValueType.EXPRESSION, StringNode.valueOf(expression))).isEqualTo(expression);
        assertThat(validator.validateAndNormalizeRaw(SecretValueType.EXPRESSION, expression)).isEqualTo(expression);
        assertThatThrownBy(() -> validator.validateAndNormalize(SecretValueType.EXPRESSION, StringNode.valueOf("  ")))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> validator.validateAndNormalize(SecretValueType.EXPRESSION, StringNode.valueOf("{{secrets.USER")))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("not closed");
        assertThatThrownBy(() -> validator.validateAndNormalize(SecretValueType.EXPRESSION, IntNode.valueOf(1)))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("EXPRESSION requires a JSON string");
    }

    private JsonNode json(String content) {
        return jsonMapper.readTree(content);
    }
}
