package app.alertify.procedures.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.alerts.execution.MaintenanceModeService;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.grpc.AlertWorkerClient;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.MissingArtifactInputException;
import app.alertify.procedures.artifact.ProcedureArtifactInput;
import app.alertify.system.SystemStatusEventPublisher;
import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.ArtifactDescriptor;
import app.alertify.worker.grpc.AlertParameterValueSource;
import app.alertify.worker.grpc.ProcedureInvocationFailureKind;
import app.alertify.worker.grpc.ProcedureParentKind;
import tools.jackson.databind.json.JsonMapper;

class ProcedureExecutionOrchestratorConcurrencyTest {
    private final ProcedureExecutionPreparationService preparation = mock(ProcedureExecutionPreparationService.class);
    private final ProcedureExecutionPersistenceService persistence = mock(ProcedureExecutionPersistenceService.class);
    private final ApplicationEventLogger eventLogger = mock(ApplicationEventLogger.class);
    private final MaintenanceModeService maintenanceModeService = mock(MaintenanceModeService.class);
    private final WorkerStatusService workerStatusService = mock(WorkerStatusService.class);
    private ProcedureExecutionOrchestrator orchestrator;
    private ProcedureGate gate;

    @BeforeEach
    void setUp() {
        orchestrator = new ProcedureExecutionOrchestrator(preparation, persistence,
                mock(ProcedureInvocationTokenService.class), mock(app.alertify.pipes.execution.PipeInvocationTokenService.class),
                mock(org.springframework.beans.factory.ObjectProvider.class), mock(ProcedureInvocationRegistry.class),
                workerStatusService, mock(AlertWorkerClient.class), mock(WorkerGrpcProperties.class),
                eventLogger, JsonMapper.builder().build(), maintenanceModeService, mock(SystemStatusEventPublisher.class));
        gate = new ProcedureGate();
        assertThat(gate.tryEnter(false)).isTrue();
        gates().put(7L, gate);
    }

    @AfterEach
    void tearDown() {
        gate.leave();
        orchestrator.close();
    }

    @Test
    void rejectsBusyManualExecutionBeforeSubmittingIt() {
        assertThat(orchestrator.triggerManual(7L, "TOTP", false, "admin")).isFalse();

        verify(preparation, never()).prepare(7L, true);
        verify(eventLogger).failure(eq("PROCEDURE_EXECUTION_REJECTED"), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void returnsTypedBusyFailureForNestedInvocationWithoutPersistingAnExecution() {
        when(preparation.prepare(7L, false, true)).thenReturn(prepared(false));
        UUID root = UUID.randomUUID();
        var claims = new ProcedureInvocationTokenService.Claims(7L, root, UUID.randomUUID(),
                ProcedureParentKind.PROCEDURE_PARENT_KIND_ALERT, 1, Instant.now().plusSeconds(30));

        var response = orchestrator.invoke(claims);

        assertThat(response.getFailure().getKind()).isEqualTo(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_BUSY);
        assertThat(response.getFailure().getExecutionId()).isEmpty();
        verify(persistence, never()).start(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void persistsMissingArtifactInputBeforeFailingWithoutReservingAWorker() {
        long procedureId = 8L;
        PreparedProcedureExecution prepared = new PreparedProcedureExecution(procedureId, 0L, "Artifact consumer", false,
                "template.ArtifactConsumer", WorkerCapability.STANDARD, false, "checksum", "source",
                List.of(new ResolvedProcedureParameter("input", ProcedureArtifactInput.class.getName(), null, null,
                        true, AlertParameterSource.PIPE_OUTPUT, null, null, null, false)));
        when(preparation.prepare(procedureId, false, true)).thenReturn(prepared);
        UUID root = UUID.randomUUID();
        var claims = new ProcedureInvocationTokenService.Claims(procedureId, root, UUID.randomUUID(),
                ProcedureParentKind.PROCEDURE_PARENT_KIND_ALERT, 1, Instant.now().plusSeconds(30));

        var response = orchestrator.invoke(claims);

        assertThat(response.getFailure().getKind()).isEqualTo(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_ERROR);
        assertThat(response.getFailure().getExecutionId()).isNotEmpty();
        assertThat(response.getFailure().getMessage()).contains("MISSING_ARTIFACT_INPUT");
        verify(persistence).start(any(), eq(prepared), eq(ProcedureExecutionTrigger.ALERT), eq(root), any(UUID.class),
                isNull(), eq(1), any(Instant.class), isNull());
        verify(persistence).failLocal(any(), any(MissingArtifactInputException.class));
        verify(workerStatusService, never()).reserve(any());
    }

    @Test
    void pipeArtifactOverridesTheNullFallbackMarker() {
        ResolvedProcedureParameter parameter = new ResolvedProcedureParameter("input",
                ProcedureArtifactInput.class.getName(), null, null, true, AlertParameterSource.PIPE_OUTPUT,
                null, null, null, false);
        ArtifactDescriptor artifact = ArtifactDescriptor.newBuilder().setArtifactId("artifact-1")
                .setOutputKey("backup").setFileName("backup.sql").setMediaType("application/sql")
                .setSize(10).setSha256(com.google.protobuf.ByteString.copyFrom(new byte[32])).build();

        var result = orchestrator.parameter(parameter, UUID.randomUUID(), UUID.randomUUID(),
                ProcedureParentKind.PROCEDURE_PARENT_KIND_PROCEDURE, 2, Instant.now().plusSeconds(30), artifact);

        assertThat(result.getNullValue()).isFalse();
        assertThat(result.getSource()).isEqualTo(AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_PIPE_OUTPUT);
        assertThat(result.getArtifact()).isEqualTo(artifact);
    }

    @Test
    void timesOutBusyHookAfterPublishingWaitingState() {
        AtomicBoolean waiting = new AtomicBoolean();
        AtomicBoolean acquired = new AtomicBoolean();

        var result = orchestrator.executeHook(7L, "TOTP", false, Duration.ofMillis(5), "hook:test",
                () -> waiting.set(true), () -> acquired.set(true));

        assertThat(result.busyTimeout()).isTrue();
        assertThat(waiting).isTrue();
        assertThat(acquired).isFalse();
        verify(persistence, never()).start(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<Long, ProcedureGate> gates() {
        return (ConcurrentMap<Long, ProcedureGate>) ReflectionTestUtils.getField(orchestrator, "procedureGates");
    }

    private static PreparedProcedureExecution prepared(boolean concurrent) {
        return new PreparedProcedureExecution(7L, 0L, "TOTP", concurrent, "template.Totp", null,
                false, "checksum", "source", List.of());
    }
}
