package app.alertify.alerts.execution;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.grpc.AlertWorkerClient;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.grpc.WorkerTemplateSynchronizationException;
import app.alertify.grpc.discovery.SelectedWorker;
import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.grpc.discovery.WorkerReservation;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.execution.ProcedureInvocationRegistry;
import app.alertify.procedures.execution.ProcedureInvocationTokenService;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator;
import app.alertify.system.SystemStatusTickerPublisher;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.AlertParameterValueSource;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.ProcedureParentKind;
import app.alertify.worker.grpc.TemplateKind;

/**
 * Drives one alert execution end to end: it prepares the alert, reserves a
 * worker, opens a bidirectional execution stream, supplies the template source
 * when the worker does not have it, and persists the outcome. Executions run on virtual threads
 * and an in-memory counter enforces the per-alert concurrency policy.
 *
 * <p>It is also the root of an execution tree: a procedure-sourced parameter is
 * sent as an invocation token instead of a value, so the worker can call the
 * procedure through the same stream within this execution's deadline.
 */
@Service
public class AlertExecutionOrchestrator implements AutoCloseable {

    private final AlertExecutionPreparationService preparationService;
    private final AlertExecutionPersistenceService persistenceService;
    private final WorkerStatusService workerStatusService;
    private final AlertWorkerClient workerClient;
    private final WorkerGrpcProperties properties;
    private final ApplicationEventLogger eventLogger;
    private final ProcedureInvocationTokenService procedureTokenService;
    private final ProcedureInvocationRegistry procedureInvocationRegistry;
    private final ProcedureExecutionOrchestrator procedureExecutionOrchestrator;
    private final CronQuietHoursService quietHoursService;
    private final MaintenanceModeService maintenanceModeService;
    private final SystemStatusTickerPublisher statusTickerPublisher;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<Long, AlertGate> alertGates = new ConcurrentHashMap<>();

    public AlertExecutionOrchestrator(AlertExecutionPreparationService preparationService, AlertExecutionPersistenceService persistenceService, WorkerStatusService workerStatusService, AlertWorkerClient workerClient, WorkerGrpcProperties properties, ApplicationEventLogger eventLogger, ProcedureInvocationTokenService procedureTokenService, ProcedureInvocationRegistry procedureInvocationRegistry, ProcedureExecutionOrchestrator procedureExecutionOrchestrator, CronQuietHoursService quietHoursService, MaintenanceModeService maintenanceModeService, SystemStatusTickerPublisher statusTickerPublisher) {
        this.preparationService = preparationService;
        this.persistenceService = persistenceService;
        this.workerStatusService = workerStatusService;
        this.workerClient = workerClient;
        this.properties = properties;
        this.eventLogger = eventLogger;
        this.procedureTokenService = procedureTokenService;
        this.procedureInvocationRegistry = procedureInvocationRegistry;
        this.procedureExecutionOrchestrator = procedureExecutionOrchestrator;
        this.quietHoursService = quietHoursService;
        this.maintenanceModeService = maintenanceModeService;
        this.statusTickerPublisher = statusTickerPublisher;
    }

    /**
     * Entry point used exclusively by the cron scheduler ({@code AlertScheduleService}).
     * Silently does nothing during the configured quiet-hours window - no
     * event is logged, unlike the {@code ALREADY_RUNNING} skip below, and
     * already-running executions or manual/hook triggers are never affected
     * since they never call this overload.
     */
    public void trigger(long alertId, String alertName, boolean allowConcurrentExecutions) {
        if (quietHoursService.isQuietNow() || maintenanceModeService.isActive())
            return;

        trigger(alertId, alertName, allowConcurrentExecutions, AlertExecutionTrigger.CRON, null);
    }

    /**
     * Submits one execution and reports whether it was accepted. The caller
     * runs on the request thread for a manual run, so {@code triggeredBy} is
     * resolved there and carried into the worker thread, where the security
     * context is no longer available.
     *
     * @return false when the alert is already running and does not allow
     *         concurrent executions.
     */
    public boolean trigger(long alertId, String alertName, boolean allowConcurrentExecutions, AlertExecutionTrigger source, String triggeredBy) {
        maintenanceModeService.assertNotActive();
        if (!enter(alertId, allowConcurrentExecutions)) {
            eventLogger.failure("ALERT_EXECUTION_SKIPPED", data(alertId, alertName, source, triggeredBy, "reason", "ALREADY_RUNNING"));
            return false;
        }
        eventLogger.success("ALERT_EXECUTION_TRIGGERED", data(alertId, alertName, source, triggeredBy));
        UUID executionId = UUID.randomUUID();
        executor.submit(() -> execute(alertId, source, triggeredBy, executionId));
        return true;
    }

    public AlertHookExecution executeHook(long alertId, String alertName, boolean allowConcurrentExecutions, Duration busyWaitTimeout, String triggeredBy, Runnable waitingCallback, Runnable acquiredCallback) {
        if (maintenanceModeService.isActive())
            return new AlertHookExecution(null, null, false, false, true);

        AlertGate gate = alertGates.computeIfAbsent(alertId, _ -> new AlertGate());
        boolean acquired;
        try {
            acquired = gate.awaitTurn(allowConcurrentExecutions, busyWaitTimeout, waitingCallback);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new AlertHookExecution(null, AlertExecutionStatus.ERROR, false, false, false);
        }
        if (!acquired) {
            cleanup(alertId, gate);
            return new AlertHookExecution(null, AlertExecutionStatus.ERROR, false, true, false);
        }

        runCallback(acquiredCallback);
        UUID executionId = UUID.randomUUID();
        eventLogger.success("ALERT_EXECUTION_TRIGGERED", data(alertId, alertName, AlertExecutionTrigger.HOOK, triggeredBy));
        AlertExecutionStatus status = execute(alertId, AlertExecutionTrigger.HOOK, triggeredBy, executionId);
        return new AlertHookExecution(status == null ? null : executionId, status, status == null, false, false);
    }

    private static Map<String, Object> data(long alertId, String alertName, AlertExecutionTrigger source, String triggeredBy, String... extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alertId", alertId);
        data.put("alertName", alertName);
        data.put("trigger", source.name());
        data.put("triggeredBy", triggeredBy == null ? "system" : triggeredBy);
        for (int index = 0; index + 1 < extra.length; index += 2)
            data.put(extra[index], extra[index + 1]);

        return data;
    }

    public boolean isRunning(long alertId) {
        AlertGate gate = alertGates.get(alertId);
        return gate != null && gate.isActive();
    }

    private AlertExecutionStatus execute(long alertId, AlertExecutionTrigger source, String triggeredBy, UUID executionId) {
        Instant startedAt = Instant.now();
        WorkerEndpoint endpoint = null;
        String workerName = null;
        String workerInstanceId = null;
        Instant deadline = startedAt.plus(properties.execution().timeout());
        persistenceService.registerTrigger(executionId, source, triggeredBy);
        try {
            // A manual run also covers alerts that are currently disabled.
            PreparedAlertExecution execution = preparationService
                    .prepare(alertId, source == AlertExecutionTrigger.MANUAL)
                    .orElse(null);
            if (execution == null)
                return null;

            try (WorkerReservation reservation = workerStatusService.reserve(execution.requiredCapability())) {
                SelectedWorker worker = reservation.worker();
                endpoint = worker.endpoint();
                workerName = worker.status().getWorkerName();
                workerInstanceId = worker.status().getWorkerInstanceId();
                Map<String, Object> started = data(execution.alertId(), execution.alertName(), source, triggeredBy);
                started.put("executionId", executionId);
                started.put("worker", endpoint.toString());
                started.put("workerName", workerName);
                started.put("workerInstanceId", workerInstanceId);
                started.put("workerLoad", worker.currentLoad());
                eventLogger.success("ALERT_EXECUTION_STARTED", started);
                statusTickerPublisher.publish();

                procedureInvocationRegistry.register(executionId, deadline);
                ExecuteAlertRequest request = request(executionId.toString(), execution, deadline);
                SynchronizeTemplateRequest templateSource = SynchronizeTemplateRequest.newBuilder().setTemplateClassName(execution.templateClassName()).setSourceChecksum(execution.sourceChecksum()).setSource(execution.source()).setTemplateKind(TemplateKind.TEMPLATE_KIND_ALERT).build();
                AlertExecutionResult result;
                try {
                    result = workerClient.executeAlert(endpoint, request, templateSource, remaining(deadline), procedureExecutionOrchestrator::invokeToken);
                } catch (WorkerTemplateSynchronizationException exception) {
                    Instant finishedAt = Instant.now();
                    persistenceService.persistRemoteFailure(alertId, executionId, endpoint, workerName, workerInstanceId, startedAt, finishedAt, finishedAt, exception.error());
                    return AlertExecutionStatus.ERROR;
                }
                persistenceService.persistWorkerResult(alertId, executionId, endpoint, result);
                return switch (result.getStatus()) {
                    case WORKER_EXECUTION_STATUS_SUCCESS -> AlertExecutionStatus.SUCCESS;
                    case WORKER_EXECUTION_STATUS_WARN -> AlertExecutionStatus.WARN;
                    default -> AlertExecutionStatus.ERROR;
                };
            }
        } catch (Throwable exception) {
            if (exception instanceof InterruptedException)
                Thread.currentThread().interrupt();

            try {
                persistenceService.persistLocalFailure(
                        alertId, executionId, endpoint, workerName, workerInstanceId,
                        startedAt, exception
                );
            } catch (RuntimeException persistenceException) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("executionId", executionId);
                data.put("alertId", alertId);
                data.put("trigger", source.name());
                data.put("triggeredBy", triggeredBy == null ? "system" : triggeredBy);
                data.put("exceptionType", exception.getClass().getName());
                if (exception.getMessage() != null)
                    data.put("exceptionMessage", exception.getMessage());

                data.put("persistenceExceptionType", persistenceException.getClass().getName());
                eventLogger.error("ALERT_EXECUTION_DISPATCH_FAILED", data);
            }
            return AlertExecutionStatus.ERROR;
        } finally {
            procedureInvocationRegistry.unregister(executionId);
            persistenceService.clearTrigger(executionId);
            leave(alertId);
            statusTickerPublisher.publish();
        }
    }

    private ExecuteAlertRequest request(String executionId, PreparedAlertExecution execution, Instant deadline) {
        ExecuteAlertRequest.Builder request = ExecuteAlertRequest.newBuilder()
                .setExecutionId(executionId)
                .setAlertId(execution.alertId())
                .setAlertName(execution.alertName())
                .setTemplateClassName(execution.templateClassName())
                .setSourceChecksum(execution.sourceChecksum())
                .setState(execution.state());
        for (ResolvedAlertParameter parameter : execution.parameters()) {
            AlertParameter.Builder value = AlertParameter.newBuilder()
                    .setName(parameter.name())
                    .setJavaType(parameter.javaType())
                    .setNullValue(parameter.source() != AlertParameterSource.PROCEDURE
                            && parameter.nullValue())
                    .setSource(toGrpcSource(parameter.source()));
            if (parameter.source() == AlertParameterSource.PROCEDURE) {
                UUID parentExecutionId = UUID.fromString(executionId);
                value.setProcedureId(parameter.procedureId())
                        .setInvocationToken(procedureTokenService.issue(parameter.procedureId(),
                                parentExecutionId, parentExecutionId,
                                ProcedureParentKind.PROCEDURE_PARENT_KIND_ALERT, 1, deadline));
            } else if (!parameter.nullValue())
                value.setValue(parameter.value());

            if (parameter.writable()) {
                boolean configurationTarget = parameter.configurationId() != null;
                boolean secretTarget = parameter.secretId() != null;
                if (configurationTarget == secretTarget)
                    throw new IllegalStateException("Writable parameter '" + parameter.name() + "' must have exactly one target");

                value.setWritable(true);
                if (configurationTarget)
                    value.setConfigurationId(parameter.configurationId());
                else
                    value.setSecretId(parameter.secretId());
            }

            request.addParameters(value);
        }
        return request.build();
    }

    private static AlertParameterValueSource toGrpcSource(AlertParameterSource source) {
        return switch (source) {
            case TEXT -> AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_TEXT;
            case CONFIGURATION -> AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_CONFIGURATION;
            case SECRET -> AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_SECRET;
            case PROCEDURE -> AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_PROCEDURE;
        };
    }

    private static java.time.Duration remaining(Instant deadline) {
        java.time.Duration value = java.time.Duration.between(Instant.now(), deadline);
        if (value.isZero() || value.isNegative())
            throw new IllegalStateException("Alert execution deadline has expired");

        return value;
    }

    private boolean enter(long alertId, boolean concurrent) {
        AlertGate gate = alertGates.computeIfAbsent(alertId, _ -> new AlertGate());
        boolean entered = gate.tryEnter(concurrent);
        if (!entered)
            cleanup(alertId, gate);

        return entered;
    }

    private void leave(long alertId) {
        AlertGate gate = alertGates.get(alertId);
        if (gate == null)
            return;

        gate.leave();
        cleanup(alertId, gate);
    }

    private void cleanup(long alertId, AlertGate gate) {
        if (gate.isIdle())
            alertGates.remove(alertId, gate);
    }

    private static void runCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Invocation state can be reconciled; callback failures must never leak an alert gate permit.
        }
    }

    public record AlertHookExecution(UUID executionId, AlertExecutionStatus status, boolean disabled, boolean busyTimeout, boolean maintenance) { }

    static final class AlertGate {
        private int active;
        private final Deque<Waiter> waiters = new ArrayDeque<>();

        synchronized boolean tryEnter(boolean concurrent) {
            if (!concurrent && (active > 0 || !waiters.isEmpty()))
                return false;

            active++;
            return true;
        }

        boolean awaitTurn(boolean concurrent, Duration timeout, Runnable waitingCallback) throws InterruptedException {
            Waiter waiter;
            synchronized (this) {
                if (concurrent || (active == 0 && waiters.isEmpty())) {
                    active++;
                    return true;
                }

                waiter = new Waiter();
                waiters.addLast(waiter);
            }

            runCallback(waitingCallback);
            synchronized (this) {
                long remaining = timeout.toNanos();
                long started = System.nanoTime();
                try {
                    while (!waiter.assigned && remaining > 0) {
                        long millis = Math.max(1, Math.min(Duration.ofNanos(remaining).toMillis(), Integer.MAX_VALUE));
                        wait(millis);
                        remaining = timeout.toNanos() - (System.nanoTime() - started);
                    }
                } catch (InterruptedException exception) {
                    if (waiter.assigned) {
                        Thread.currentThread().interrupt();
                        return true;
                    }

                    waiters.remove(waiter);
                    throw exception;
                }
                if (waiter.assigned)
                    return true;

                waiters.remove(waiter);
                return false;
            }
        }

        synchronized void leave() {
            active--;
            Waiter next = active == 0 ? waiters.pollFirst() : null;
            if (next != null) {
                active = 1;
                next.assigned = true;
                notifyAll();
            }
        }

        synchronized boolean isActive() { return active > 0; }
        synchronized boolean isIdle() { return active == 0 && waiters.isEmpty(); }
    }

    private static final class Waiter { private boolean assigned; }

    @Override
    public void close() {
        executor.close();
    }
}
