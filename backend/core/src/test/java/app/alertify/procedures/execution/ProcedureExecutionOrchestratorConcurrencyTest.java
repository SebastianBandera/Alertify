package app.alertify.procedures.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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

import app.alertify.grpc.AlertWorkerClient;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.grpc.ProcedureInvocationFailureKind;
import app.alertify.worker.grpc.ProcedureParentKind;
import tools.jackson.databind.json.JsonMapper;

class ProcedureExecutionOrchestratorConcurrencyTest {
    private final ProcedureExecutionPreparationService preparation = mock(ProcedureExecutionPreparationService.class);
    private final ProcedureExecutionPersistenceService persistence = mock(ProcedureExecutionPersistenceService.class);
    private final ApplicationEventLogger eventLogger = mock(ApplicationEventLogger.class);
    private ProcedureExecutionOrchestrator orchestrator;
    private ProcedureGate gate;

    @BeforeEach
    void setUp() {
        orchestrator = new ProcedureExecutionOrchestrator(preparation, persistence,
                mock(ProcedureInvocationTokenService.class), mock(ProcedureInvocationRegistry.class),
                mock(WorkerStatusService.class), mock(AlertWorkerClient.class), mock(WorkerGrpcProperties.class),
                eventLogger, JsonMapper.builder().build());
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
        when(preparation.prepare(7L, false)).thenReturn(prepared(false));
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
