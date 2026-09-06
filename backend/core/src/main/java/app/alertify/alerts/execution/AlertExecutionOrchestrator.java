package app.alertify.alerts.execution;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<Long, AtomicInteger> activeAlerts = new ConcurrentHashMap<>();

    public AlertExecutionOrchestrator(AlertExecutionPreparationService preparationService, AlertExecutionPersistenceService persistenceService, WorkerStatusService workerStatusService, AlertWorkerClient workerClient, WorkerGrpcProperties properties, ApplicationEventLogger eventLogger, ProcedureInvocationTokenService procedureTokenService, ProcedureInvocationRegistry procedureInvocationRegistry, ProcedureExecutionOrchestrator procedureExecutionOrchestrator) {
        this.preparationService = preparationService;
        this.persistenceService = persistenceService;
        this.workerStatusService = workerStatusService;
        this.workerClient = workerClient;
        this.properties = properties;
        this.eventLogger = eventLogger;
        this.procedureTokenService = procedureTokenService;
        this.procedureInvocationRegistry = procedureInvocationRegistry;
        this.procedureExecutionOrchestrator = procedureExecutionOrchestrator;
    }

    public void trigger(long alertId, String alertName, boolean allowConcurrentExecutions) {
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
        if (!enter(alertId, allowConcurrentExecutions)) {
            eventLogger.failure("ALERT_EXECUTION_SKIPPED", data(alertId, alertName, source, triggeredBy, "reason", "ALREADY_RUNNING"));
            return false;
        }
        eventLogger.success("ALERT_EXECUTION_TRIGGERED", data(alertId, alertName, source, triggeredBy));
        executor.submit(() -> execute(alertId, source, triggeredBy));
        return true;
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
        AtomicInteger count = activeAlerts.get(alertId);
        return count != null && count.get() > 0;
    }

    private void execute(long alertId, AlertExecutionTrigger source, String triggeredBy) {
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        WorkerEndpoint endpoint = null;
        String workerName = null;
        String workerInstanceId = null;
        Instant deadline = startedAt.plus(properties.execution().timeout());
        try {
            // A manual run also covers alerts that are currently disabled.
            PreparedAlertExecution execution = preparationService
                    .prepare(alertId, source == AlertExecutionTrigger.MANUAL)
                    .orElse(null);
            if (execution == null)
                return;

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

                procedureInvocationRegistry.register(executionId, deadline);
                ExecuteAlertRequest request = request(executionId.toString(), execution, deadline);
                SynchronizeTemplateRequest templateSource = SynchronizeTemplateRequest.newBuilder().setTemplateClassName(execution.templateClassName()).setSourceChecksum(execution.sourceChecksum()).setSource(execution.source()).setTemplateKind(TemplateKind.TEMPLATE_KIND_ALERT).build();
                AlertExecutionResult result;
                try {
                    result = workerClient.executeAlert(endpoint, request, templateSource, remaining(deadline), procedureExecutionOrchestrator::invokeToken);
                } catch (WorkerTemplateSynchronizationException exception) {
                    Instant finishedAt = Instant.now();
                    persistenceService.persistRemoteFailure(alertId, executionId, endpoint, workerName, workerInstanceId, startedAt, finishedAt, finishedAt, exception.error());
                    return;
                }
                persistenceService.persistWorkerResult(alertId, executionId, endpoint, result);
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
        } finally {
            procedureInvocationRegistry.unregister(executionId);
            leave(alertId);
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
        if (!concurrent)
            return activeAlerts.putIfAbsent(alertId, new AtomicInteger(1)) == null;

        activeAlerts.compute(alertId, (key, count) -> {
            if (count == null)
                return new AtomicInteger(1);

            count.incrementAndGet();
            return count;
        });
        return true;
    }

    private void leave(long alertId) {
        activeAlerts.computeIfPresent(alertId, (key, count) ->
            count.decrementAndGet() <= 0 ? null : count
        );
    }

    @Override
    public void close() {
        executor.close();
    }
}
