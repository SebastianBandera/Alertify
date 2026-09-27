package app.alertify.alerts.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.alertify.alerts.AlertExecutionValue;
import app.alertify.alerts.AlertExecutionValueSource;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertExecution;
import app.alertify.alerts.model.AlertState;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.configuration.service.WritableConfigurationService;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.services.secret.WritableSecretService;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.ExecutionError;
import app.alertify.worker.grpc.WorkerExecutionStatus;
import app.alertify.worker.contract.WorkerCapability;
import com.google.protobuf.Timestamp;
import tools.jackson.databind.json.JsonMapper;

class AlertExecutionPersistenceServiceTest {

    @Test
    void keepsWorkerWarnAndStateWhenItsTimestampsAreReversed() {
        AlertRepository alerts = mock(AlertRepository.class);
        AlertExecutionRepository executions = mock(AlertExecutionRepository.class);
        AlertStateRepository states = mock(AlertStateRepository.class);
        WritableConfigurationService configurations = mock(WritableConfigurationService.class);
        WritableSecretService secrets = mock(WritableSecretService.class);
        Alert alert = mock(Alert.class);
        AlertState state = mock(AlertState.class);
        when(alert.getId()).thenReturn(2L);
        when(alert.getName()).thenReturn("Demo WARN");
        when(alerts.findById(2L)).thenReturn(Optional.of(alert));
        when(states.findById(2L)).thenReturn(Optional.of(state));
        AlertExecutionPersistenceService service = new AlertExecutionPersistenceService(alerts, executions,
                states, mock(ApplicationEventLogger.class), JsonMapper.builder().build(), configurations, secrets);
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-09-25T15:00:00Z");
        String secret = "{\"host\":\"db.internal\",\"username\":\"monitor\",\"password\":\"opaque-password\"}";
        PreparedAlertExecution prepared = new PreparedAlertExecution(
                2L, "Demo WARN", "dynamic.SecretWarning", WorkerCapability.STANDARD,
                "a".repeat(64), "source", "state", List.of(new ResolvedAlertParameter(
                        "credentials", String.class.getName(), secret, false,
                        AlertParameterSource.SECRET, null, 9L, false
                ))
        );
        AlertExecutionResult result = AlertExecutionResult.newBuilder()
                .setStatus(WorkerExecutionStatus.WORKER_EXECUTION_STATUS_WARN)
                .setStartedAt(timestamp(startedAt))
                .setWorkStartedAt(timestamp(startedAt.minusMillis(1)))
                .setFinishedAt(timestamp(startedAt.plusMillis(5)))
                .setStatusMessageJson("{\"message\":\"warn monitor with opaque-password\",\"nested\":{\"host\":\"db.internal\"}}")
                .setState("updated by monitor at db.internal")
                .build();

        service.persistWorkerResult(2L, executionId, null, result, prepared);

        ArgumentCaptor<AlertExecution> saved = ArgumentCaptor.forClass(AlertExecution.class);
        verify(executions).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AlertExecutionStatus.WARN);
        assertThat(saved.getValue().getStartedAt()).isEqualTo(startedAt);
        assertThat(saved.getValue().getWorkStartedAt()).isEqualTo(startedAt);
        assertThat(saved.getValue().getFinishedAt()).isEqualTo(startedAt.plusMillis(5));
        assertThat(saved.getValue().getStatusMessage().get("message").asText()).isEqualTo("warn [REDACTED] with [REDACTED]");
        assertThat(saved.getValue().getStatusMessage().get("nested").get("host").asText()).isEqualTo("[REDACTED]");
        verify(state).replaceState("updated by [REDACTED] at [REDACTED]");
        verify(configurations).apply(2L, "Demo WARN", executionId, result.getWritableConfigurationValuesList());
        verify(secrets).apply(2L, "Demo WARN", executionId, result.getWritableSecretValuesList());
    }

    @Test
    void redactsSecretDiagnosticsAndOmitsTheMessageFromTheAuditEvent() {
        AlertRepository alerts = mock(AlertRepository.class);
        AlertExecutionRepository executions = mock(AlertExecutionRepository.class);
        AlertStateRepository states = mock(AlertStateRepository.class);
        ApplicationEventLogger eventLogger = mock(ApplicationEventLogger.class);
        Alert alert = mock(Alert.class);
        AlertState state = mock(AlertState.class);
        when(alert.getId()).thenReturn(2L);
        when(alert.getName()).thenReturn("Secret failure");
        when(alerts.findById(2L)).thenReturn(Optional.of(alert));
        when(states.findById(2L)).thenReturn(Optional.of(state));
        AlertExecutionPersistenceService service = new AlertExecutionPersistenceService(alerts, executions,
                states, eventLogger, JsonMapper.builder().build(), mock(WritableConfigurationService.class),
                mock(WritableSecretService.class));
        String secret = "{\"username\":\"monitor\",\"password\":\"opaque-password\"}";
        String preparedSecret = "prepared-private";
        PreparedAlertExecution prepared = new PreparedAlertExecution(
                2L, "Secret failure", "dynamic.SecretFailure", WorkerCapability.STANDARD,
                "a".repeat(64), "source", "state", List.of(new ResolvedAlertParameter(
                        "credentials", String.class.getName(), secret, false,
                        AlertParameterSource.SECRET, null, 9L, false
                )), List.of(new AlertExecutionValue(AlertExecutionValueSource.SECRET, "Login Password", preparedSecret))
        );
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-09-25T15:00:00Z");
        AlertExecutionResult result = AlertExecutionResult.newBuilder()
                .setStatus(WorkerExecutionStatus.WORKER_EXECUTION_STATUS_ERROR)
                .setStartedAt(timestamp(startedAt))
                .setWorkStartedAt(timestamp(startedAt.plusMillis(1)))
                .setFinishedAt(timestamp(startedAt.plusMillis(2)))
                .setState("state for monitor with opaque-password and " + preparedSecret)
                .setError(ExecutionError.newBuilder()
                        .setType("example.DatabaseFailure")
                        .setMessage("Login monitor failed with opaque-password and " + preparedSecret)
                        .setStackTrace("credentials=" + secret + "; prepared=" + preparedSecret))
                .build();

        service.persistWorkerResult(2L, executionId, null, result, prepared);

        ArgumentCaptor<AlertExecution> saved = ArgumentCaptor.forClass(AlertExecution.class);
        verify(executions).save(saved.capture());
        assertThat(saved.getValue().getErrorMessage()).isEqualTo("Login [REDACTED] failed with [REDACTED] and [REDACTED]");
        assertThat(saved.getValue().getErrorStackTrace()).isEqualTo("credentials=[REDACTED]; prepared=[REDACTED]");
        verify(state).replaceState("state for [REDACTED] with [REDACTED] and [REDACTED]");
        ArgumentCaptor<Map<String, Object>> auditData = ArgumentCaptor.captor();
        verify(eventLogger).errorAfterCommit(org.mockito.ArgumentMatchers.eq("ALERT_EXECUTION_COMPLETED"), auditData.capture());
        assertThat(auditData.getValue()).containsEntry("errorType", "example.DatabaseFailure");
        assertThat(auditData.getValue()).doesNotContainKey("errorMessage");
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }
}
