package app.alertify.alerts.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertParameterValue;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.AlertExecutionValueSource;
import app.alertify.alerts.templates.PlaywrightPageAlertTemplate;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.pipes.execution.PipeParameterValue;
import app.alertify.services.secret.SecretAccessService;
import app.alertify.services.secret.SecretAccessContext;
import app.alertify.binary.BinaryBindingService;
import app.alertify.worker.contract.WorkerCapability;
import app.alertify.configuration.service.ConfigurationExpressionService;
import tools.jackson.databind.node.StringNode;

@ExtendWith(MockitoExtension.class)
class AlertExecutionPreparationServiceTest {

    @TempDir private Path sourceRoot;

    @Mock private AlertRepository alertRepository;
    @Mock private AlertTemplateParameterDefinitionRepository definitionRepository;
    @Mock private AlertParameterValueRepository parameterValueRepository;
    @Mock private AlertStateRepository stateRepository;
    @Mock private ConfigurationExpressionService configurationExpressionService;
    @Mock private SecretAccessService secretAccessService;
    @Mock private ApplicationConfigurationRepository configurationRepository;
    @Mock private ApplicationSecretRepository secretRepository;
    @Mock private BinaryBindingService binaryBindingService;

    @Test
    void preparesWritableSecretBindingWithItsDecryptedValueAndTargetId() throws Exception {
        AlertTemplateDefinition template = new AlertTemplateDefinition(
                "dynamic.SecretAlert", "name", "description", "SecretAlert.java",
                WorkerCapability.STANDARD
        );
        ReflectionTestUtils.setField(template, "id", 2L);
        AlertTemplateParameterDefinition definition = new AlertTemplateParameterDefinition(
                template, "token", "token", "token", String.class.getName(),
                List.of(), true, null, false, 0, true,
                List.of(AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION, AlertParameterSource.SECRET),
                List.of(), List.of()
        );
        ReflectionTestUtils.setField(definition, "id", 3L);
        Alert alert = new Alert(template, "Secret alert", null, "0 0 * * * *", true);
        ReflectionTestUtils.setField(alert, "id", 7L);
        ApplicationSecret secret = new ApplicationSecret(
                "api.token", null, "cipher-value-here".getBytes(StandardCharsets.UTF_8),
                new byte[12], new byte[32], new byte[16], (short) 1, Set.of(), true
        );
        ReflectionTestUtils.setField(secret, "id", 73L);
        AlertParameterValue configured = AlertParameterValue.secret(alert, definition, secret);
        Files.writeString(sourceRoot.resolve("SecretAlert.java"), "source", StandardCharsets.UTF_8);

        when(alertRepository.findById(7L)).thenReturn(Optional.of(alert));
        when(parameterValueRepository.findAllByAlertIdOrdered(7L)).thenReturn(List.of(configured));
        when(definitionRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(2L))
                .thenReturn(List.of(definition));
        when(stateRepository.findById(7L)).thenReturn(Optional.empty());
        when(secretAccessService.getValueByName(eq("api.token"), any(SecretAccessContext.class))).thenReturn("decrypted-token");

        PreparedAlertExecution execution = service().prepare(7L).orElseThrow();

        assertThat(execution.parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.value()).isEqualTo("decrypted-token");
            assertThat(parameter.source()).isEqualTo(AlertParameterSource.SECRET);
            assertThat(parameter.writable()).isTrue();
            assertThat(parameter.secretId()).isEqualTo(73L);
            assertThat(parameter.configurationId()).isNull();
            assertThat(parameter.bindingVersion()).isEqualTo(0L);
        });
        verify(secretAccessService).getValueByName(eq("api.token"), org.mockito.ArgumentMatchers.argThat(context ->
                context.consumerType() == SecretAccessContext.ConsumerType.ALERT
                        && context.consumerId() == 7L
                        && context.consumerName().equals("Secret alert")));
    }

    @Test
    void resolvesBinarySecretsThroughTheAuditedAccessBoundary() throws Exception {
        AlertTemplateDefinition template = new AlertTemplateDefinition(
                "dynamic.BinaryAlert", "name", "description", "BinaryAlert.java",
                WorkerCapability.STANDARD);
        ReflectionTestUtils.setField(template, "id", 22L);
        AlertTemplateParameterDefinition definition = new AlertTemplateParameterDefinition(
                template, "certificate", "certificate", "certificate", byte[].class.getName(),
                List.of(), true, null, false, 0, false, List.of(AlertParameterSource.SECRET), List.of(), List.of());
        ReflectionTestUtils.setField(definition, "id", 23L);
        Alert alert = new Alert(template, "Certificate alert", null, "0 0 * * * *", true);
        ReflectionTestUtils.setField(alert, "id", 27L);
        ApplicationSecret secret = new ApplicationSecret(
                "client.certificate", null, SecretValueType.BINARY, "cipher-value-here".getBytes(StandardCharsets.UTF_8),
                new byte[12], new byte[32], new byte[16], (short) 1, Set.of(), false);
        ReflectionTestUtils.setField(secret, "id", 83L);
        AlertParameterValue configured = AlertParameterValue.secret(alert, definition, secret);
        byte[] binaryValue = { 1, 2, 3 };
        Files.writeString(sourceRoot.resolve("BinaryAlert.java"), "source", StandardCharsets.UTF_8);

        when(alertRepository.findById(27L)).thenReturn(Optional.of(alert));
        when(parameterValueRepository.findAllByAlertIdOrdered(27L)).thenReturn(List.of(configured));
        when(definitionRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(22L)).thenReturn(List.of(definition));
        when(stateRepository.findById(27L)).thenReturn(Optional.empty());
        when(secretAccessService.getBinaryValue(eq(secret), any(SecretAccessContext.class))).thenReturn(binaryValue);

        PreparedAlertExecution execution = service().prepare(27L).orElseThrow();

        assertThat(execution.parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.value()).isNull();
            assertThat(parameter.binaryZip()).isSameAs(binaryValue);
            assertThat(parameter.secretId()).isEqualTo(83L);
        });
        verify(secretAccessService).getBinaryValue(eq(secret), org.mockito.ArgumentMatchers.argThat(context ->
                context.consumerType() == SecretAccessContext.ConsumerType.ALERT
                        && context.consumerId() == 27L
                        && context.consumerName().equals("Certificate alert")));
    }

    @Test
    void preparesOnlyReferencedPlaywrightValuesAndResolvesThemAgainForTheNextExecution() throws Exception {
        AlertTemplateDefinition template = new AlertTemplateDefinition(
                PlaywrightPageAlertTemplate.class.getName(), "name", "description", "PlaywrightPageAlertTemplate.java",
                WorkerCapability.PLAYWRIGHT
        );
        ReflectionTestUtils.setField(template, "id", 12L);
        AlertTemplateParameterDefinition definition = new AlertTemplateParameterDefinition(
                template, "steps", "steps", "steps", String.class.getName(),
                List.of(), true, null, true, 7, false,
                List.of(AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION, AlertParameterSource.SECRET),
                List.of(), List.of()
        );
        ReflectionTestUtils.setField(definition, "id", 13L);
        Alert alert = new Alert(template, "Form alert", null, "0 0 * * * *", true);
        ReflectionTestUtils.setField(alert, "id", 17L);
        AlertParameterValue configured = AlertParameterValue.text(alert, definition, """
            QUERY input[name=user]
            FILL configs.Login User
            QUERY input[name=alias]
            FILL configs.login user
            QUERY input[name=password]
            FILL secrets.Login Password
            """);
        ApplicationConfiguration configuration = new ApplicationConfiguration(
                "LOGIN USER", null, ConfigurationValueType.EXPRESSION, StringNode.valueOf("expression"), Set.of()
        );
        ApplicationSecret secret = new ApplicationSecret(
                "LOGIN PASSWORD", null, SecretValueType.EXPRESSION, "cipher-value-here".getBytes(StandardCharsets.UTF_8),
                new byte[12], new byte[32], new byte[16], (short) 1, Set.of(), false
        );
        Files.writeString(sourceRoot.resolve("PlaywrightPageAlertTemplate.java"), "source", StandardCharsets.UTF_8);

        when(alertRepository.findById(17L)).thenReturn(Optional.of(alert));
        when(parameterValueRepository.findAllByAlertIdOrdered(17L)).thenReturn(List.of(configured));
        when(definitionRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(12L)).thenReturn(List.of(definition));
        when(stateRepository.findById(17L)).thenReturn(Optional.empty());
        when(configurationRepository.findByNameIgnoreCase("Login User")).thenReturn(Optional.of(configuration));
        when(secretRepository.findByNameIgnoreCase("Login Password")).thenReturn(Optional.of(secret));
        when(configurationExpressionService.getResolvedValueByName("LOGIN USER")).thenReturn("user-one", "user-two");
        when(secretAccessService.getValueByName(eq("LOGIN PASSWORD"), any(SecretAccessContext.class))).thenReturn("password-one", "password-two");

        PreparedAlertExecution first = service().prepare(17L).orElseThrow();
        PreparedAlertExecution second = service().prepare(17L).orElseThrow();

        assertThat(first.preparedValues()).containsExactly(
                new app.alertify.alerts.AlertExecutionValue(AlertExecutionValueSource.CONFIGURATION, "LOGIN USER", "user-one"),
                new app.alertify.alerts.AlertExecutionValue(AlertExecutionValueSource.SECRET, "LOGIN PASSWORD", "password-one")
        );
        assertThat(second.preparedValues()).extracting(app.alertify.alerts.AlertExecutionValue::value)
                .containsExactly("user-two", "password-two");
        verify(secretAccessService, times(2)).getValueByName(eq("LOGIN PASSWORD"), org.mockito.ArgumentMatchers.argThat(context ->
                context.consumerType() == SecretAccessContext.ConsumerType.ALERT
                        && context.consumerId() == 17L
                        && context.consumerName().equals("Form alert")));
    }

    @Test
    void appliesSensitivePipeOverridesWithoutChangingTheConfiguredFallback() throws Exception {
        AlertTemplateDefinition template = new AlertTemplateDefinition(
                "dynamic.PipeAlert", "name", "description", "PipeAlert.java", WorkerCapability.STANDARD);
        ReflectionTestUtils.setField(template, "id", 42L);
        AlertTemplateParameterDefinition definition = new AlertTemplateParameterDefinition(
                template, "headersOverrideJson", "headers", "headers", String.class.getName(),
                List.of(), true, null, true, 0, false,
                List.of(AlertParameterSource.TEXT), List.of(), List.of());
        ReflectionTestUtils.setField(definition, "id", 43L);
        Alert alert = new Alert(template, "Pipe alert", null, "-", true);
        ReflectionTestUtils.setField(alert, "id", 47L);
        AlertParameterValue configured = AlertParameterValue.text(alert, definition, "[\"Accept: application/json\"]");
        Files.writeString(sourceRoot.resolve("PipeAlert.java"), "source", StandardCharsets.UTF_8);

        when(alertRepository.findById(47L)).thenReturn(Optional.of(alert));
        when(parameterValueRepository.findAllByAlertIdOrdered(47L)).thenReturn(List.of(configured));
        when(definitionRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(42L)).thenReturn(List.of(definition));
        when(stateRepository.findById(47L)).thenReturn(Optional.empty());

        PreparedAlertExecution execution = service().prepare(47L, false,
                Map.of("headersOverrideJson", new PipeParameterValue("[\"Authorization: Bearer derived-token\"]", true)))
                .orElseThrow();

        assertThat(execution.parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.value()).isEqualTo("[\"Authorization: Bearer derived-token\"]");
            assertThat(parameter.source()).isEqualTo(AlertParameterSource.PIPE_OUTPUT);
            assertThat(parameter.sensitive()).isTrue();
            assertThat(parameter.configurationId()).isNull();
            assertThat(parameter.secretId()).isNull();
        });
        assertThat(configured.getTextValue()).isEqualTo("[\"Accept: application/json\"]");
    }

    @Test
    void rejectsPipeOverridesForWritableRequiredAlertParameters() throws Exception {
        AlertTemplateDefinition template = new AlertTemplateDefinition(
                "dynamic.WritablePipeAlert", "name", "description", "WritablePipeAlert.java", WorkerCapability.STANDARD);
        ReflectionTestUtils.setField(template, "id", 52L);
        AlertTemplateParameterDefinition definition = new AlertTemplateParameterDefinition(
                template, "state", "state", "state", String.class.getName(),
                List.of(), true, true, null, false, 0, true,
                List.of(AlertParameterSource.CONFIGURATION), List.of(), List.of());
        ReflectionTestUtils.setField(definition, "id", 53L);
        Alert alert = new Alert(template, "Writable Pipe alert", null, "-", true);
        ReflectionTestUtils.setField(alert, "id", 57L);
        Files.writeString(sourceRoot.resolve("WritablePipeAlert.java"), "source", StandardCharsets.UTF_8);

        when(alertRepository.findById(57L)).thenReturn(Optional.of(alert));
        when(parameterValueRepository.findAllByAlertIdOrdered(57L)).thenReturn(List.of());
        when(definitionRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(52L)).thenReturn(List.of(definition));

        assertThatThrownBy(() -> service().prepare(57L, false,
                Map.of("state", new PipeParameterValue("derived", true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a bindable String");
    }

    private AlertExecutionPreparationService service() {
        return new AlertExecutionPreparationService(
                alertRepository, definitionRepository, parameterValueRepository, stateRepository,
                configurationExpressionService, secretAccessService,
                configurationRepository, secretRepository,
                new WorkerGrpcProperties(
                        "worker", 9090, null, null,
                        new WorkerGrpcProperties.Execution(null, sourceRoot)
                ), binaryBindingService
        );
    }
}
