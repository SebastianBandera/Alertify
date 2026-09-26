package app.alertify.alerts.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertExecution;
import app.alertify.alerts.model.AlertState;
import app.alertify.configuration.service.WritableConfigurationService;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.services.secret.WritableSecretService;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.WorkerExecutionStatus;
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
        AlertExecutionResult result = AlertExecutionResult.newBuilder()
                .setStatus(WorkerExecutionStatus.WORKER_EXECUTION_STATUS_WARN)
                .setStartedAt(timestamp(startedAt))
                .setWorkStartedAt(timestamp(startedAt.minusMillis(1)))
                .setFinishedAt(timestamp(startedAt.plusMillis(5)))
                .setStatusMessageJson("{\"message\":\"warn\"}")
                .setState("updated")
                .build();

        service.persistWorkerResult(2L, executionId, null, result);

        ArgumentCaptor<AlertExecution> saved = ArgumentCaptor.forClass(AlertExecution.class);
        verify(executions).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AlertExecutionStatus.WARN);
        assertThat(saved.getValue().getStartedAt()).isEqualTo(startedAt);
        assertThat(saved.getValue().getWorkStartedAt()).isEqualTo(startedAt);
        assertThat(saved.getValue().getFinishedAt()).isEqualTo(startedAt.plusMillis(5));
        assertThat(saved.getValue().getStatusMessage().get("message").asText()).isEqualTo("warn");
        verify(state).replaceState("updated");
        verify(configurations).apply(2L, "Demo WARN", executionId, result.getWritableConfigurationValuesList());
        verify(secrets).apply(2L, "Demo WARN", executionId, result.getWritableSecretValuesList());
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }
}
