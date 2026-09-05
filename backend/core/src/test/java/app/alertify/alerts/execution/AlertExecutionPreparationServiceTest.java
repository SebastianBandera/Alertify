package app.alertify.alerts.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.services.secret.SecretAccessService;
import app.alertify.worker.contract.WorkerCapability;
import app.alertify.configuration.service.ConfigurationExpressionService;

@ExtendWith(MockitoExtension.class)
class AlertExecutionPreparationServiceTest {

    @TempDir private Path sourceRoot;

    @Mock private AlertRepository alertRepository;
    @Mock private AlertTemplateParameterDefinitionRepository definitionRepository;
    @Mock private AlertParameterValueRepository parameterValueRepository;
    @Mock private AlertStateRepository stateRepository;
    @Mock private ConfigurationExpressionService configurationExpressionService;
    @Mock private SecretAccessService secretAccessService;

    @Test
    void preparesWritableSecretBindingWithItsDecryptedValueAndTargetId() throws Exception {
        AlertTemplateDefinition template = new AlertTemplateDefinition(
                "dynamic.SecretAlert", "name", "description", "SecretAlert.java",
                WorkerCapability.STANDARD
        );
        ReflectionTestUtils.setField(template, "id", 2L);
        AlertTemplateParameterDefinition definition = new AlertTemplateParameterDefinition(
                template, "token", "token", "token", String.class.getName(),
                List.of(), true, null, false, 0, true
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
        when(secretAccessService.getValueByName("api.token")).thenReturn("decrypted-token");

        PreparedAlertExecution execution = service().prepare(7L).orElseThrow();

        assertThat(execution.parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.value()).isEqualTo("decrypted-token");
            assertThat(parameter.source()).isEqualTo(AlertParameterSource.SECRET);
            assertThat(parameter.writable()).isTrue();
            assertThat(parameter.secretId()).isEqualTo(73L);
            assertThat(parameter.configurationId()).isNull();
        });
    }

    private AlertExecutionPreparationService service() {
        return new AlertExecutionPreparationService(
                alertRepository, definitionRepository, parameterValueRepository, stateRepository,
                configurationExpressionService, secretAccessService,
                new WorkerGrpcProperties(
                        "worker", 9090, null, null,
                        new WorkerGrpcProperties.Execution(null, null, sourceRoot)
                )
        );
    }
}
