package app.alertify.procedures.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.alertify.configuration.service.WritableConfigurationService;
import app.alertify.jpa.repository.ProcedureArtifactMetadataRepository;
import app.alertify.jpa.repository.ProcedureExecutionRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureExecution;
import app.alertify.services.secret.WritableSecretService;
import app.alertify.worker.grpc.ProcedureExecutionResult;
import com.google.protobuf.Timestamp;
import tools.jackson.databind.json.JsonMapper;

class ProcedureExecutionPersistenceServiceTest {

    @Test
    void completesProcedureWhenWorkerClockIsBehindBackendClock() {
        ProcedureExecutionRepository executions = mock(ProcedureExecutionRepository.class);
        WritableConfigurationService configurations = mock(WritableConfigurationService.class);
        WritableSecretService secrets = mock(WritableSecretService.class);
        Procedure procedure = mock(Procedure.class);
        when(procedure.getId()).thenReturn(3L);
        when(procedure.getName()).thenReturn("Sample procedure");
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-09-25T15:00:00Z");
        ProcedureExecution execution = ProcedureExecution.running(executionId, procedure, 1L,
                ProcedureExecutionTrigger.MANUAL, executionId, null, null, 0, startedAt, "admin");
        when(executions.findByExecutionId(executionId)).thenReturn(Optional.of(execution));
        ProcedureExecutionPersistenceService service = new ProcedureExecutionPersistenceService(
                mock(ProcedureRepository.class), executions, configurations, secrets,
                mock(ApplicationEventLogger.class), JsonMapper.builder().build(),
                mock(ProcedureArtifactMetadataRepository.class));
        ProcedureExecutionResult result = ProcedureExecutionResult.newBuilder()
                .setSuccessful(true)
                .setWorkStartedAt(timestamp(startedAt.minusMillis(2)))
                .setFinishedAt(timestamp(startedAt.minusMillis(1)))
                .setResultJson("{}")
                .build();

        service.complete(executionId, null, result, false);

        assertThat(execution.getStatus()).isEqualTo(ProcedureExecutionStatus.COMPLETED);
        assertThat(execution.getWorkStartedAt()).isEqualTo(startedAt);
        assertThat(execution.getFinishedAt()).isEqualTo(startedAt);
        assertThat(execution.getResultJson().isObject()).isTrue();
        verify(configurations).applyProcedure(3L, "Sample procedure", executionId,
                result.getWritableConfigurationValuesList());
        verify(secrets).applyProcedure(3L, "Sample procedure", executionId,
                result.getWritableSecretValuesList());
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }
}
