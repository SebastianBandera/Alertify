package app.alertify.pipes.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.execution.AlertExecutionOrchestrator;
import app.alertify.alerts.execution.MaintenanceModeService;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.pipes.model.*;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator;
import app.alertify.procedures.execution.ProcedureExecutionProperties;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureTemplateOutputDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import app.alertify.worker.grpc.ArtifactDescriptor;
import tools.jackson.databind.json.JsonMapper;

class PipeFinallyTest {
    @Test
    void finallyReceivesArtifactsProducedByMainBeforeTheyAreDeleted() {
        var persistence = mock(PipeExecutionPersistenceService.class);
        var procedures = mock(ProcedureExecutionOrchestrator.class);
        Pipe pipe = mock(Pipe.class);
        when(pipe.getId()).thenReturn(1L);
        when(pipe.isEnabled()).thenReturn(true);
        when(pipe.getFinallyTimeoutMillis()).thenReturn(60_000L);
        when(persistence.definition(1L)).thenReturn(pipe);
        PipeStep main = step(pipe, "main", 0, 11, PipeStepPhase.MAIN);
        PipeStep cleanup = step(pipe, "cleanup", 1, 12, PipeStepPhase.FINALLY);
        var parameter = mock(ProcedureTemplateParameterDefinition.class);
        var output = mock(ProcedureTemplateOutputDefinition.class);
        when(parameter.getParameterKey()).thenReturn("input");
        when(output.getOutputKey()).thenReturn("report");
        cleanup.addBinding(new PipeStepBinding(cleanup, parameter, main, output));
        when(pipe.getSteps()).thenReturn(List.of(main, cleanup));
        var artifact = new ProcedureExecutionOrchestrator.ArtifactLocation(
                ArtifactDescriptor.newBuilder().setOutputKey("report").build(), null, UUID.randomUUID());
        when(procedures.executePipeStep(eq(11L), any(), any(), anyInt(), any(), any(), anyMap()))
                .thenReturn(new ProcedureExecutionOrchestrator.ProcedurePipeExecution(UUID.randomUUID(), null,
                        artifact.workerInstanceId(), null, List.of(artifact), List.of()));
        when(procedures.executePipeStep(eq(12L), any(), any(), anyInt(), any(), any(), anyMap())).thenAnswer(invocation -> {
            assertThat(invocation.<java.util.Map<String, ProcedureExecutionOrchestrator.ArtifactLocation>>getArgument(6))
                    .containsEntry("input", artifact);
            verify(procedures, never()).deleteArtifacts(anyList());
            return new ProcedureExecutionOrchestrator.ProcedurePipeExecution(UUID.randomUUID(), null, null, null, List.of(), List.of());
        });
        try (var orchestrator = orchestrator(persistence, procedures)) {
            orchestrator.invoke(new PipeInvocationTokenService.Claims(1, UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now().plusSeconds(30)));
        }
        verify(procedures).deleteArtifacts(List.of(artifact));
    }

    @Test
    void runsEveryFinallyStepAfterMainFailureAndKeepsErrorOutcome() {
        var persistence = mock(PipeExecutionPersistenceService.class);
        var procedures = mock(ProcedureExecutionOrchestrator.class);
        Pipe pipe = mock(Pipe.class);
        when(pipe.getId()).thenReturn(1L);
        when(pipe.isEnabled()).thenReturn(true);
        when(pipe.getFinallyTimeoutMillis()).thenReturn(60_000L);
        when(persistence.definition(1L)).thenReturn(pipe);
        PipeStep main = step(pipe, "main", 0, 11, PipeStepPhase.MAIN);
        PipeStep skipped = step(pipe, "skipped", 1, 12, PipeStepPhase.MAIN);
        PipeStep cleanup = step(pipe, "cleanup", 2, 13, PipeStepPhase.FINALLY);
        PipeStep last = step(pipe, "last", 3, 14, PipeStepPhase.FINALLY);
        when(pipe.getSteps()).thenReturn(List.of(main, skipped, cleanup, last));
        when(procedures.executePipeStep(eq(11L), any(), any(), anyInt(), any(), any(), anyMap()))
                .thenThrow(new IllegalStateException("failure"));
        when(procedures.executePipeStep(eq(13L), any(), any(), anyInt(), any(), any(), anyMap()))
                .thenThrow(new IllegalStateException("cleanup failure"));
        when(procedures.executePipeStep(eq(14L), any(), any(), anyInt(), any(), any(), anyMap()))
                .thenReturn(new ProcedureExecutionOrchestrator.ProcedurePipeExecution(UUID.randomUUID(), null, null, null, List.of(), List.of()));
        try (var orchestrator = orchestrator(persistence, procedures)) {
            var response = orchestrator.invoke(new PipeInvocationTokenService.Claims(1, UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now().plusSeconds(30)));
            assertThat(response.hasResult()).isTrue();
            assertThat(response.getResult().getResultJson()).contains("ERROR");
        }
        verify(persistence).completeStep(any(), eq("skipped"), eq(PipeStepStatus.SKIPPED_SEQUENCE), isNull(), isNull(), isNull());
        verify(procedures).executePipeStep(eq(14L), any(), any(), anyInt(), any(), any(), anyMap());
        verify(persistence).finish(any(), eq(PipeExecutionStatus.FAILED), eq(PipeOutcome.ERROR), isNull());
    }

    @Test
    void expiredMainDeadlineDoesNotConsumeFinallyBudget() {
        var persistence = mock(PipeExecutionPersistenceService.class);
        var procedures = mock(ProcedureExecutionOrchestrator.class);
        Pipe pipe = mock(Pipe.class);
        when(pipe.getId()).thenReturn(1L);
        when(pipe.isEnabled()).thenReturn(true);
        when(pipe.getFinallyTimeoutMillis()).thenReturn(60_000L);
        when(persistence.definition(1L)).thenReturn(pipe);
        PipeStep main = step(pipe, "main", 0, 11, PipeStepPhase.MAIN);
        PipeStep cleanup = step(pipe, "cleanup", 1, 12, PipeStepPhase.FINALLY);
        when(pipe.getSteps()).thenReturn(List.of(main, cleanup));
        when(procedures.executePipeStep(eq(12L), any(), any(), anyInt(), any(), any(), anyMap())).thenAnswer(invocation -> {
            assertThat((Instant) invocation.getArgument(4)).isAfter(Instant.now());
            return new ProcedureExecutionOrchestrator.ProcedurePipeExecution(UUID.randomUUID(), null, null, null, List.of(), List.of());
        });
        try (var orchestrator = orchestrator(persistence, procedures)) {
            orchestrator.invoke(new PipeInvocationTokenService.Claims(1, UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now().minusSeconds(1)));
        }
        verify(procedures, never()).executePipeStep(eq(11L), any(), any(), anyInt(), any(), any(), anyMap());
        verify(procedures).executePipeStep(eq(12L), any(), any(), anyInt(), any(), any(), anyMap());
    }

    private static PipeStep step(Pipe pipe, String key, int position, long id, PipeStepPhase phase) {
        Procedure procedure = mock(Procedure.class);
        when(procedure.getId()).thenReturn(id);
        when(procedure.isEnabled()).thenReturn(true);
        PipeStep step = PipeStep.procedure(pipe, key, position, procedure, 30_000, List.of("SUCCESS"));
        step.setPhase(phase);
        return step;
    }

    private static PipeExecutionOrchestrator orchestrator(PipeExecutionPersistenceService persistence, ProcedureExecutionOrchestrator procedures) {
        return new PipeExecutionOrchestrator(persistence, mock(AlertExecutionOrchestrator.class), procedures,
                mock(MaintenanceModeService.class), new ProcedureExecutionProperties(5), mock(ApplicationEventLogger.class), JsonMapper.builder().build());
    }
}
