package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidConfigurationExpressionException;
import app.alertify.api.error.InvalidSecretValueException;
import app.alertify.configuration.service.ConfigurationExpressionParser;
import app.alertify.configuration.service.ConfigurationExpressionService;
import app.alertify.configuration.service.ConfigurationExpressionUtilityResolver;
import app.alertify.configuration.service.EnvironmentVariableResolver;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.logging.ApplicationEventLogger;

@ExtendWith(MockitoExtension.class)
class SecretExpressionServiceTest {

    @Mock private ApplicationSecretRepository secretRepository;
    @Mock private ApplicationConfigurationRepository configurationRepository;
    @Mock private SecretExpressionDependencyRepository dependencyRepository;
    @Mock private SecretEncryptionService encryptionService;
    @Mock private ConfigurationExpressionService configurationExpressionService;
    @Mock private ApplicationEventLogger eventLogger;

    private SecretExpressionService service;

    @BeforeEach
    void setUp() {
        service = new SecretExpressionService(
                secretRepository, configurationRepository, dependencyRepository, encryptionService,
                new ConfigurationExpressionParser(), configurationExpressionService,
                new EnvironmentVariableResolver(""), new ConfigurationExpressionUtilityResolver(), eventLogger
        );
    }

    @Test
    void returnsPlainSecretsDecrypted() {
        ApplicationSecret plain = secret(1L, "API_PASS", SecretValueType.STRING);
        when(encryptionService.decrypt(plain)).thenReturn("s3cret");

        assertThat(service.resolve(plain)).isEqualTo("s3cret");
    }

    @Test
    void evaluatesExpressionsAcrossSecretsConfigurationsAndFunctions() {
        ApplicationSecret user = secret(1L, "API_USER", SecretValueType.STRING);
        ApplicationSecret pass = secret(2L, "API_PASS", SecretValueType.STRING);
        ApplicationSecret basic = secret(3L, "API_BASIC", SecretValueType.EXPRESSION);
        ApplicationSecret headers = secret(4L, "API_HEADERS", SecretValueType.EXPRESSION);
        when(secretRepository.findByNameIgnoreCase("API_USER")).thenReturn(Optional.of(user));
        when(secretRepository.findByNameIgnoreCase("API_PASS")).thenReturn(Optional.of(pass));
        when(secretRepository.findByNameIgnoreCase("API_BASIC")).thenReturn(Optional.of(basic));
        when(encryptionService.decrypt(user)).thenReturn("user");
        when(encryptionService.decrypt(pass)).thenReturn("pass");
        when(encryptionService.decrypt(basic)).thenReturn("Basic {{utils.BASE64({{secrets.API_USER}}:{{secrets.API_PASS}})}}");
        when(encryptionService.decrypt(headers)).thenReturn("[\"Authorization: {{secrets.API_BASIC}}\", \"X-Realm: {{configs.REALM}}\"]");
        when(configurationExpressionService.getResolvedValueByName("REALM")).thenReturn("prod");

        String value = service.resolve(headers);

        assertThat(value).isEqualTo("[\"Authorization: Basic dXNlcjpwYXNz\", \"X-Realm: prod\"]");
        verify(eventLogger).success(eq("SECRET_VALUE_ACCESSED"), org.mockito.ArgumentMatchers.argThat(data -> data.get("name").equals("API_PASS")));
    }

    @Test
    void detectsCyclesWhileResolving() {
        ApplicationSecret a = secret(1L, "A", SecretValueType.EXPRESSION);
        ApplicationSecret b = secret(2L, "B", SecretValueType.EXPRESSION);
        when(secretRepository.findByNameIgnoreCase("A")).thenReturn(Optional.of(a));
        when(secretRepository.findByNameIgnoreCase("B")).thenReturn(Optional.of(b));
        when(encryptionService.decrypt(a)).thenReturn("{{secrets.B}}");
        when(encryptionService.decrypt(b)).thenReturn("{{secrets.A}}");

        assertThatThrownBy(() -> service.resolve(a))
                .isInstanceOf(InvalidConfigurationExpressionException.class)
                .hasMessageContaining("cycle detected at 'A'");
    }

    @Test
    void synchronizesDependenciesAndRejectsUnknownReferences() {
        ApplicationSecret user = secret(1L, "API_USER", SecretValueType.STRING);
        ApplicationSecret basic = secret(3L, "API_BASIC", SecretValueType.EXPRESSION);
        ApplicationConfiguration realm = mock(ApplicationConfiguration.class);
        when(realm.getId()).thenReturn(10L);
        when(secretRepository.findByNameIgnoreCase("API_USER")).thenReturn(Optional.of(user));
        when(configurationRepository.findByNameIgnoreCase("REALM")).thenReturn(Optional.of(realm));
        when(dependencyRepository.findReferencedSecretIds(3L)).thenReturn(List.of(1L));
        when(dependencyRepository.findReferencedSecretIds(1L)).thenReturn(List.of());

        service.synchronizeDependencies(basic, "{{secrets.API_USER}}@{{configs.REALM}}");

        verify(dependencyRepository).replace(3L, Set.of(1L), Set.of(10L));

        when(secretRepository.findByNameIgnoreCase("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.synchronizeDependencies(basic, "{{secrets.NOPE}}"))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("referenced secret 'NOPE' was not found");
    }

    @Test
    void clearsDependenciesForPlainSecrets() {
        service.synchronizeDependencies(secret(5L, "PLAIN", SecretValueType.STRING), "value");

        verify(dependencyRepository).replace(5L, Set.of(), Set.of());
    }

    @Test
    void rejectsDraftsThatWouldCreateACycle() {
        ApplicationSecret a = secret(1L, "A", SecretValueType.EXPRESSION);
        ApplicationSecret b = secret(2L, "B", SecretValueType.EXPRESSION);
        when(secretRepository.findByNameIgnoreCase("B")).thenReturn(Optional.of(b));
        when(secretRepository.findById(1L)).thenReturn(Optional.of(a));
        when(secretRepository.findById(2L)).thenReturn(Optional.of(b));
        when(dependencyRepository.findReferencedSecretIds(2L)).thenReturn(List.of(1L));

        assertThatThrownBy(() -> service.validateDraft(1L, "A", "{{secrets.B}}"))
                .isInstanceOf(InvalidSecretValueException.class)
                .hasMessageContaining("cycle");

        when(dependencyRepository.findReferencedSecretIds(2L)).thenReturn(List.of());
        service.validateDraft(1L, "A", "{{secrets.B}}");
        verify(eventLogger).success(eq("SECRET_EXPRESSION_VALIDATED"), anyMap());
    }

    @Test
    void blocksRenamingOrDeletingReferencedSecrets() {
        ApplicationSecret user = secret(1L, "API_USER", SecretValueType.STRING);
        when(dependencyRepository.findDependentSecretNames(1L)).thenReturn(List.of("API_BASIC"));

        assertThatThrownBy(() -> service.ensureNotReferenced(user, "deleted"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("API_BASIC")
                .extracting("code").isEqualTo("SECRET_REFERENCED_BY_EXPRESSION");
    }

    private static ApplicationSecret secret(Long id, String name, SecretValueType type) {
        ApplicationSecret secret = new ApplicationSecret(
                name, null, type, "cipher".getBytes(StandardCharsets.UTF_8),
                new byte[12], new byte[32], new byte[16], (short) 1, Set.of(), false
        );
        ReflectionTestUtils.setField(secret, "id", id);
        return secret;
    }
}
