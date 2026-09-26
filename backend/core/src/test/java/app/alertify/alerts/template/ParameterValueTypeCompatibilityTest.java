package app.alertify.alerts.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.GitCredentials;
import app.alertify.worker.contract.KubeconfigCredentials;
import app.alertify.worker.contract.OidcTokenSet;

class ParameterValueTypeCompatibilityTest {

    private static final String BYTE_ARRAY = byte[].class.getName();
    private static final String DATABASE_CREDENTIALS = DatabaseCredentials.class.getName();
    private static final String GIT_CREDENTIALS = GitCredentials.class.getName();
    private static final String OIDC_TOKEN_SET = OidcTokenSet.class.getName();
    private static final String KUBECONFIG_CREDENTIALS = KubeconfigCredentials.class.getName();
    private static final String STRING = String.class.getName();

    @Test
    void requiresBinaryOnlyForByteArrayFields() {
        assertThat(ParameterValueTypeCompatibility.requiredConfigurationValueType(BYTE_ARRAY))
                .contains(ConfigurationValueType.BINARY);
        assertThat(ParameterValueTypeCompatibility.requiredConfigurationValueType(STRING)).isEmpty();
    }

    @Test
    void requiresBinaryOrDbSecretForTheirRespectiveJavaTypes() {
        assertThat(ParameterValueTypeCompatibility.requiredSecretValueType(BYTE_ARRAY))
                .contains(SecretValueType.BINARY);
        assertThat(ParameterValueTypeCompatibility.requiredSecretValueType(DATABASE_CREDENTIALS))
                .contains(SecretValueType.DB_SECRET);
        assertThat(ParameterValueTypeCompatibility.requiredSecretValueType(GIT_CREDENTIALS))
                .contains(SecretValueType.GIT_SECRET);
        assertThat(ParameterValueTypeCompatibility.requiredSecretValueType(OIDC_TOKEN_SET))
                .contains(SecretValueType.OIDC_TOKEN_SET);
        assertThat(ParameterValueTypeCompatibility.requiredSecretValueType(KUBECONFIG_CREDENTIALS))
                .contains(SecretValueType.KUBECONFIG);
        assertThat(ParameterValueTypeCompatibility.requiredSecretValueType(STRING)).isEmpty();
    }

    @Test
    void configurationCompatibilityMirrorsThePhysicalRequirement() {
        assertThat(ParameterValueTypeCompatibility.isConfigurationValueTypeCompatible(BYTE_ARRAY, ConfigurationValueType.BINARY)).isTrue();
        assertThat(ParameterValueTypeCompatibility.isConfigurationValueTypeCompatible(BYTE_ARRAY, ConfigurationValueType.STRING)).isFalse();
        assertThat(ParameterValueTypeCompatibility.isConfigurationValueTypeCompatible(STRING, ConfigurationValueType.STRING)).isTrue();
        assertThat(ParameterValueTypeCompatibility.isConfigurationValueTypeCompatible(STRING, ConfigurationValueType.BINARY)).isFalse();
        assertThat(ParameterValueTypeCompatibility.isConfigurationValueTypeCompatible(OIDC_TOKEN_SET, ConfigurationValueType.STRING)).isFalse();
    }

    @Test
    void secretCompatibilityMirrorsThePhysicalRequirement() {
        assertThat(ParameterValueTypeCompatibility.isSecretValueTypeCompatible(DATABASE_CREDENTIALS, SecretValueType.DB_SECRET)).isTrue();
        assertThat(ParameterValueTypeCompatibility.isSecretValueTypeCompatible(STRING, SecretValueType.DB_SECRET)).isFalse();
        assertThat(ParameterValueTypeCompatibility.isSecretValueTypeCompatible(STRING, SecretValueType.STRING)).isTrue();
        assertThat(ParameterValueTypeCompatibility.isSecretValueTypeCompatible(BYTE_ARRAY, SecretValueType.BINARY)).isTrue();
        assertThat(ParameterValueTypeCompatibility.isSecretValueTypeCompatible(GIT_CREDENTIALS, SecretValueType.GIT_SECRET)).isTrue();
        assertThat(ParameterValueTypeCompatibility.isSecretValueTypeCompatible(GIT_CREDENTIALS, SecretValueType.STRING)).isFalse();
        assertThat(ParameterValueTypeCompatibility.isSecretValueTypeCompatible(STRING, SecretValueType.GIT_SECRET)).isFalse();
        assertThat(ParameterValueTypeCompatibility.isSecretValueTypeCompatible(OIDC_TOKEN_SET, SecretValueType.OIDC_TOKEN_SET)).isTrue();
        assertThat(ParameterValueTypeCompatibility.isSecretValueTypeCompatible(STRING, SecretValueType.OIDC_TOKEN_SET)).isFalse();
        assertThat(ParameterValueTypeCompatibility.isSecretValueTypeCompatible(KUBECONFIG_CREDENTIALS, SecretValueType.KUBECONFIG)).isTrue();
        assertThat(ParameterValueTypeCompatibility.isSecretValueTypeCompatible(STRING, SecretValueType.KUBECONFIG)).isFalse();
    }

    @Test
    void emptyDeclarationDefaultsToTheJavaTypesPhysicalRequirement() {
        assertThat(ParameterValueTypeCompatibility.effectiveAllowedConfigurationValueTypes(BYTE_ARRAY, List.of(), "field"))
                .containsExactly("BINARY");
        assertThat(ParameterValueTypeCompatibility.effectiveAllowedSecretValueTypes(DATABASE_CREDENTIALS, List.of(), "field"))
                .containsExactly("DB_SECRET");
        assertThat(ParameterValueTypeCompatibility.effectiveAllowedSecretValueTypes(OIDC_TOKEN_SET, List.of(), "field"))
                .containsExactly("OIDC_TOKEN_SET");
        assertThat(ParameterValueTypeCompatibility.effectiveAllowedConfigurationValueTypes(STRING, List.of(), "field")).isEmpty();
    }

    @Test
    void declaringTheMatchingRequiredTypeIsAccepted() {
        assertThat(ParameterValueTypeCompatibility.effectiveAllowedConfigurationValueTypes(BYTE_ARRAY, List.of("BINARY"), "field"))
                .containsExactly("BINARY");
        assertThat(ParameterValueTypeCompatibility.effectiveAllowedSecretValueTypes(STRING, List.of("STRING", "EXPRESSION"), "field"))
                .containsExactly("STRING", "EXPRESSION");
    }

    @Test
    void rejectsDeclaringBinaryForANonByteArrayField() {
        assertThatIllegalStateException()
                .isThrownBy(() -> ParameterValueTypeCompatibility.effectiveAllowedConfigurationValueTypes(STRING, List.of("BINARY"), "field"))
                .withMessageContaining("BINARY");
    }

    @Test
    void rejectsDeclaringAMismatchedTypeForAByteArrayField() {
        assertThatIllegalStateException()
                .isThrownBy(() -> ParameterValueTypeCompatibility.effectiveAllowedConfigurationValueTypes(BYTE_ARRAY, List.of("STRING"), "field"));
    }

    @Test
    void rejectsDeclaringGitSecretForAJavaTypeThatDoesNotRequireIt() {
        assertThatIllegalStateException()
                .isThrownBy(() -> ParameterValueTypeCompatibility.effectiveAllowedSecretValueTypes(STRING, List.of("GIT_SECRET"), "field"))
                .withMessageContaining("GIT_SECRET");
    }

    @Test
    void rejectsAnUnknownValueTypeName() {
        assertThatIllegalStateException()
                .isThrownBy(() -> ParameterValueTypeCompatibility.effectiveAllowedSecretValueTypes(STRING, List.of("NOPE"), "field"))
                .withMessageContaining("NOPE");
    }

    @Test
    void excludesTextWhenTheJavaTypeRequiresABinaryOrDatabaseSecretBinding() {
        assertThatIllegalStateException()
                .isThrownBy(() -> ParameterValueTypeCompatibility.validateAllowedSourcesForRequiredTypes(
                        BYTE_ARRAY, Set.of(AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION), "field"));
        assertThatIllegalStateException()
                .isThrownBy(() -> ParameterValueTypeCompatibility.validateAllowedSourcesForRequiredTypes(
                        DATABASE_CREDENTIALS, Set.of(AlertParameterSource.TEXT, AlertParameterSource.SECRET), "field"));
        assertThatIllegalStateException()
                .isThrownBy(() -> ParameterValueTypeCompatibility.validateAllowedSourcesForRequiredTypes(
                        OIDC_TOKEN_SET, Set.of(AlertParameterSource.CONFIGURATION, AlertParameterSource.SECRET), "field"));

        ParameterValueTypeCompatibility.validateAllowedSourcesForRequiredTypes(
                BYTE_ARRAY, Set.of(AlertParameterSource.CONFIGURATION, AlertParameterSource.SECRET), "field");
        ParameterValueTypeCompatibility.validateAllowedSourcesForRequiredTypes(
                STRING, Set.of(AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION, AlertParameterSource.SECRET), "field");
        ParameterValueTypeCompatibility.validateAllowedSourcesForRequiredTypes(
                OIDC_TOKEN_SET, Set.of(AlertParameterSource.SECRET), "field");
    }
}
