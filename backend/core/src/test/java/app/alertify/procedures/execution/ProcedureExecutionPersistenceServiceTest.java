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

    @Test
    void keepsJwtVariantsAvailableToThePipeButRedactsPersistedResults() {
        var executions = mock(ProcedureExecutionRepository.class);
        var procedure = mock(Procedure.class);
        when(procedure.getId()).thenReturn(3L);
        when(procedure.getName()).thenReturn("JWT negative tests");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        var execution = ProcedureExecution.running(id, procedure, 1L, ProcedureExecutionTrigger.PIPE, id, null, null, 0, now, "admin");
        when(executions.findByExecutionId(id)).thenReturn(Optional.of(execution));
        var mapper = JsonMapper.builder().build();
        var variants = new app.alertify.procedures.templates.JwtNegativeTestTokensProcedureTemplate("eyJhbGciOiJSUzI1NiJ9.e30.AQID").execute(null);
        var service = new ProcedureExecutionPersistenceService(mock(ProcedureRepository.class), executions,
                mock(WritableConfigurationService.class), mock(WritableSecretService.class),
                mock(ApplicationEventLogger.class), mapper, mock(ProcedureArtifactMetadataRepository.class));
        var result = ProcedureExecutionResult.newBuilder().setSuccessful(true).setWorkStartedAt(timestamp(now))
                .setFinishedAt(timestamp(now.plusMillis(1))).setResultJson(mapper.writeValueAsString(variants)).build();
        var ephemeral = service.complete(id, null, result, true);
        assertThat(ephemeral.equals(variants)).isTrue();
        assertThat(execution.isResultRedacted()).isTrue();
        assertThat(execution.getResultJson()).isNull();
    }
}
