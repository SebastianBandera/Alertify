package app.alertify.hooks.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import app.alertify.alerts.execution.AlertExecutionOrchestrator;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.model.Alert;
import app.alertify.hooks.model.HookInvocation;
import app.alertify.hooks.model.HookInvocationTarget;
import app.alertify.hooks.model.HookMode;
import app.alertify.hooks.model.HookTargetType;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.pipes.execution.PipeExecutionOrchestrator;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator;

class HookCoordinatorOriginTest {

    @ParameterizedTest
    @EnumSource(HookMode.class)
    void passesTheInvocationSnapshotToAlertExecution(HookMode mode) {
        HookInvocationPersistenceService persistence = mock(HookInvocationPersistenceService.class);
        AlertRepository alerts = mock(AlertRepository.class);
        AlertExecutionOrchestrator orchestrator = mock(AlertExecutionOrchestrator.class);
        UUID invocationId = UUID.randomUUID();
        HookInvocation invocation = mock(HookInvocation.class);
        HookInvocationTarget target = mock(HookInvocationTarget.class);
        Alert alert = mock(Alert.class);
        when(persistence.execution(invocationId)).thenReturn(invocation);
        when(invocation.getInvocationId()).thenReturn(invocationId);
        when(invocation.getHookName()).thenReturn("Hook name at acceptance");
        when(invocation.getMode()).thenReturn(mode);
        when(invocation.getTargets()).thenReturn(List.of(target));
        when(target.getTargetType()).thenReturn(HookTargetType.ALERT);
        when(target.getResourceId()).thenReturn(2L);
        when(target.getResourceName()).thenReturn("Target alert");
        when(target.getBusyWaitTimeoutMillis()).thenReturn(1_000L);
        when(target.getContinueOn()).thenReturn(List.of("SUCCESS"));
        when(alerts.findById(2L)).thenReturn(Optional.of(alert));
        when(alert.getId()).thenReturn(2L);
        when(alert.isEnabled()).thenReturn(true);
        when(orchestrator.executeHook(eq(2L), eq("Target alert"), eq(false), eq(Duration.ofSeconds(1)),
                eq("hook:" + invocationId), any(), any(), eq(invocationId), eq("Hook name at acceptance")))
                .thenReturn(new AlertExecutionOrchestrator.AlertHookExecution(UUID.randomUUID(), AlertExecutionStatus.SUCCESS, false, false, false));

        try (HookCoordinator coordinator = new HookCoordinator(persistence, mock(HookAdmissionService.class), alerts,
                mock(ProcedureRepository.class), mock(PipeRepository.class), orchestrator,
                mock(ProcedureExecutionOrchestrator.class), mock(PipeExecutionOrchestrator.class), mock(ApplicationEventLogger.class))) {
            coordinator.submit(invocationId, 1L, false);
            verify(persistence, timeout(2_000)).finish(invocationId);
            verify(orchestrator).executeHook(eq(2L), eq("Target alert"), eq(false), eq(Duration.ofSeconds(1)),
                    eq("hook:" + invocationId), any(), any(), eq(invocationId), eq("Hook name at acceptance"));
        }
    }
}
