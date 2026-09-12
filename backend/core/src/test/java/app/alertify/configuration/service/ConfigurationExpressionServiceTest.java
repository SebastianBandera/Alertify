package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidConfigurationExpressionException;
import app.alertify.configuration.api.ConfigurationExpressionEvaluationRequest;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.services.secret.SecretExpressionDependencyRepository;
import tools.jackson.databind.node.StringNode;

@ExtendWith(MockitoExtension.class)
class ConfigurationExpressionServiceTest {

    @Mock private ApplicationConfigurationRepository configurationRepository;
    @Mock private ConfigurationExpressionDependencyRepository dependencyRepository;
    @Mock private SecretExpressionDependencyRepository secretDependencyRepository;
    @Mock private ApplicationEventLogger eventLogger;

    private ConfigurationExpressionService service;

    @BeforeEach
    void setUp() {
        service = new ConfigurationExpressionService(
                configurationRepository, dependencyRepository, secretDependencyRepository,
                new ConfigurationExpressionParser(), new EnvironmentVariableResolver(""),
                new ConfigurationExpressionUtilityResolver(), eventLogger
        );
    }

    @Test
    void evaluatesUtilityFunctionsOverNestedReferences() {
        ApplicationConfiguration user = configuration("API_USER", "user");
        when(configurationRepository.findByNameIgnoreCase("API_USER")).thenReturn(Optional.of(user));

        var response = service.evaluate(new ConfigurationExpressionEvaluationRequest(
                null, null, "Basic {{utils.BASE64({{configs.API_USER}}:pass)}}"
        ));

        assertThat(response.value()).isEqualTo("Basic dXNlcjpwYXNz");
    }

    @Test
    void neverResolvesSecretsFromConfigurationExpressions() {
        assertThatThrownBy(() -> service.evaluate(new ConfigurationExpressionEvaluationRequest(
                null, null, "{{secrets.API_PASS}}"
        )))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("secrets cannot be referenced from configuration expressions");
    }

    @Test
    void blocksChangesToConfigurationsReferencedBySecretExpressions() {
        ApplicationConfiguration realm = configuration("REALM", "prod");
        when(dependencyRepository.findDependentNames(7L)).thenReturn(List.of());
        when(secretDependencyRepository.findDependentSecretNamesForConfiguration(7L)).thenReturn(List.of("API_BASIC"));

        assertThatThrownBy(() -> service.ensureNotReferenced(realm, "deleted"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("referenced by secrets: API_BASIC")
                .extracting("code").isEqualTo("CONFIGURATION_REFERENCED_BY_SECRET_EXPRESSION");
    }

    private static ApplicationConfiguration configuration(String name, String value) {
        ApplicationConfiguration configuration = mock(ApplicationConfiguration.class);
        lenient().when(configuration.getId()).thenReturn(7L);
        lenient().when(configuration.getName()).thenReturn(name);
        lenient().when(configuration.getValueType()).thenReturn(ConfigurationValueType.STRING);
        lenient().when(configuration.getValue()).thenReturn(StringNode.valueOf(value));
        return configuration;
    }
}
