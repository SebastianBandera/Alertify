package app.alertify.hooks.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import app.alertify.alerts.execution.AlertExecutionOrchestrator;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.model.Alert;
import app.alertify.hooks.model.HookInvocation;
import app.alertify.hooks.model.HookInvocationTarget;
import app.alertify.hooks.model.HookMode;
import app.alertify.hooks.model.HookOutcome;
import app.alertify.hooks.model.HookTargetStatus;
import app.alertify.hooks.model.HookTargetType;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator;
import app.alertify.procedures.model.Procedure;

@Service
public class HookCoordinator implements AutoCloseable {

    private final HookInvocationPersistenceService persistence;
    private final HookAdmissionService admission;
    private final AlertRepository alertRepository;
    private final ProcedureRepository procedureRepository;
    private final AlertExecutionOrchestrator alertOrchestrator;
    private final ProcedureExecutionOrchestrator procedureOrchestrator;
    private final ApplicationEventLogger eventLogger;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService leaseExecutor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("hook-lease-renewal").factory());

    public HookCoordinator(HookInvocationPersistenceService persistence, HookAdmissionService admission, AlertRepository alertRepository, ProcedureRepository procedureRepository, AlertExecutionOrchestrator alertOrchestrator, ProcedureExecutionOrchestrator procedureOrchestrator, ApplicationEventLogger eventLogger) {
        this.persistence = persistence;
        this.admission = admission;
        this.alertRepository = alertRepository;
        this.procedureRepository = procedureRepository;
        this.alertOrchestrator = alertOrchestrator;
        this.procedureOrchestrator = procedureOrchestrator;
        this.eventLogger = eventLogger;
    }

    public void submit(UUID invocationId, long hookId, boolean limited) {
        executor.submit(() -> run(invocationId, hookId, limited));
    }

    private void run(UUID invocationId, long hookId, boolean limited) {
        Future<?> renewal = limited ? leaseExecutor.scheduleAtFixedRate(
                () -> admission.renew(hookId, invocationId), admission.renewalInterval().toMillis(),
                admission.renewalInterval().toMillis(), TimeUnit.MILLISECONDS) : null;
        try {
            HookInvocation invocation = persistence.execution(invocationId);
            if (invocation.getMode() == HookMode.PARALLEL)
                parallel(invocation);
            else
                sequential(invocation);

            persistence.finish(invocationId);
        } catch (Throwable exception) {
            eventLogger.error("HOOK_INVOCATION_COMPLETED", Map.of("invocationId", invocationId, "status", "INTERRUPTED", "exceptionType", exception.getClass().getName()));
            try {
                persistence.reconcileInterrupted(invocationId);
            } catch (RuntimeException persistenceException) {
                eventLogger.error("HOOK_INVOCATION_COMPLETED", Map.of("invocationId", invocationId, "status", "PERSISTENCE_ERROR", "exceptionType", persistenceException.getClass().getName()));
            }
        } finally {
            if (renewal != null)
                renewal.cancel(false);

            if (limited)
                admission.release(hookId, invocationId);
        }
    }

    private void parallel(HookInvocation invocation) throws Exception {
        List<Future<TargetResult>> results = new ArrayList<>();
        for (HookInvocationTarget target : invocation.getTargets())
            results.add(executor.submit(() -> executeSafely(target, invocation.getInvocationId())));

        Exception failure = null;
        for (Future<TargetResult> result : results) {
            try {
                result.get();
            } catch (Exception exception) {
                if (failure == null)
                    failure = exception;
            }
        }
        if (failure != null)
            throw failure;
    }

    private void sequential(HookInvocation invocation) {
        List<HookInvocationTarget> targets = invocation.getTargets();
        boolean continueSequence = true;
        for (HookInvocationTarget target : targets) {
            if (!continueSequence) {
                persistence.completeTarget(target.getId(), HookTargetStatus.SKIPPED_SEQUENCE, null, null, null);
                continue;
            }

            TargetResult result = executeSafely(target, invocation.getInvocationId());
            if (result.disabled())
                continue;

            continueSequence = result.outcome() != null && target.getContinueOn().contains(result.outcome().name());
        }
    }

    private TargetResult execute(HookInvocationTarget target, UUID invocationId) {
        String actor = "hook:" + invocationId;
        if (target.getTargetType() == HookTargetType.ALERT) {
            Alert alert = alertRepository.findById(target.getResourceId()).orElse(null);
            if (alert == null || !alert.isEnabled()) {
                persistence.completeTarget(target.getId(), HookTargetStatus.SKIPPED_DISABLED, null, null, null);
                return new TargetResult(null, true);
            }

            AlertExecutionOrchestrator.AlertHookExecution execution = alertOrchestrator.executeHook(
                    alert.getId(), target.getResourceName(), alert.isConcurrentExecutionAllowed(),
                    Duration.ofMillis(target.getBusyWaitTimeoutMillis()), actor,
                    () -> persistence.transitionTarget(target.getId(), HookTargetStatus.WAITING_ALERT),
                    () -> persistence.transitionTarget(target.getId(), HookTargetStatus.RUNNING)
            );
            if (execution.busyTimeout()) {
                persistence.completeTarget(target.getId(), HookTargetStatus.ALERT_BUSY_TIMEOUT, HookOutcome.ERROR, null, "ALERT_BUSY_TIMEOUT");
                return new TargetResult(HookOutcome.ERROR, false);
            }
            if (execution.maintenance()) {
                persistence.completeTarget(target.getId(), HookTargetStatus.SKIPPED_MAINTENANCE, null, null, null);
                return new TargetResult(null, true);
            }
            if (execution.disabled()) {
                persistence.completeTarget(target.getId(), HookTargetStatus.SKIPPED_DISABLED, null, null, null);
                return new TargetResult(null, true);
            }

            HookOutcome outcome = switch (execution.status()) {
                case SUCCESS -> HookOutcome.SUCCESS;
                case WARN -> HookOutcome.WARN;
                case ERROR -> HookOutcome.ERROR;
            };
            HookTargetStatus status = HookTargetStatus.valueOf(outcome.name());
            persistence.completeTarget(target.getId(), status, outcome, execution.executionId(), null);
            return new TargetResult(outcome, false);
        }

        Procedure procedure = procedureRepository.findById(target.getResourceId()).orElse(null);
        if (procedure == null || !procedure.isEnabled()) {
            persistence.completeTarget(target.getId(), HookTargetStatus.SKIPPED_DISABLED, null, null, null);
            return new TargetResult(null, true);
        }

        ProcedureExecutionOrchestrator.ProcedureHookExecution execution = procedureOrchestrator.executeHook(
                procedure.getId(), target.getResourceName(), procedure.isConcurrentExecutionAllowed(),
                Duration.ofMillis(target.getBusyWaitTimeoutMillis()), actor,
                () -> persistence.transitionTarget(target.getId(), HookTargetStatus.WAITING_PROCEDURE),
                () -> persistence.transitionTarget(target.getId(), HookTargetStatus.RUNNING)
        );
        if (execution.busyTimeout()) {
            persistence.completeTarget(target.getId(), HookTargetStatus.PROCEDURE_BUSY_TIMEOUT, HookOutcome.ERROR, null, "PROCEDURE_BUSY_TIMEOUT");
            return new TargetResult(HookOutcome.ERROR, false);
        }
        if (execution.maintenance()) {
            persistence.completeTarget(target.getId(), HookTargetStatus.SKIPPED_MAINTENANCE, null, null, null);
            return new TargetResult(null, true);
        }
        if (execution.disabled()) {
            persistence.completeTarget(target.getId(), HookTargetStatus.SKIPPED_DISABLED, null, null, null);
            return new TargetResult(null, true);
        }

        HookOutcome outcome = execution.successful() ? HookOutcome.SUCCESS : HookOutcome.ERROR;
        persistence.completeTarget(target.getId(), HookTargetStatus.valueOf(outcome.name()), outcome, execution.executionId(), null);
        return new TargetResult(outcome, false);
    }

    private TargetResult executeSafely(HookInvocationTarget target, UUID invocationId) {
        try {
            return execute(target, invocationId);
        } catch (Throwable exception) {
            if (exception instanceof InterruptedException)
                Thread.currentThread().interrupt();

            persistence.completeTarget(target.getId(), HookTargetStatus.ERROR, HookOutcome.ERROR, target.getExecutionId(), "HOOK_TARGET_FAILURE");
            return new TargetResult(HookOutcome.ERROR, false);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileInterrupted() {
        for (HookInvocation invocation : persistence.running())
            persistence.reconcileInterrupted(invocation.getInvocationId());
    }

    @Override
    public void close() {
        leaseExecutor.close();
        executor.close();
    }

    private record TargetResult(HookOutcome outcome, boolean disabled) { }
}
