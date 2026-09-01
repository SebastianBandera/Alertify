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

import app.alertify.grpc.AlertWorkerClient;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.grpc.discovery.SelectedWorker;
import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.grpc.discovery.WorkerReservation;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.ExecuteAlertResponse;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.SynchronizeTemplateResponse;

@Service
public class AlertExecutionOrchestrator implements AutoCloseable {

    private final AlertExecutionPreparationService preparationService;
    private final AlertExecutionPersistenceService persistenceService;
    private final WorkerStatusService workerStatusService;
    private final AlertWorkerClient workerClient;
    private final WorkerGrpcProperties properties;
    private final ApplicationEventLogger eventLogger;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<Long, AtomicInteger> activeAlerts = new ConcurrentHashMap<>();

    public AlertExecutionOrchestrator(AlertExecutionPreparationService preparationService, AlertExecutionPersistenceService persistenceService, WorkerStatusService workerStatusService, AlertWorkerClient workerClient, WorkerGrpcProperties properties, ApplicationEventLogger eventLogger) {
        this.preparationService = preparationService;
        this.persistenceService = persistenceService;
        this.workerStatusService = workerStatusService;
        this.workerClient = workerClient;
        this.properties = properties;
        this.eventLogger = eventLogger;
    }

    public void trigger(long alertId, String alertName, boolean allowConcurrentExecutions) {
        if (!enter(alertId, allowConcurrentExecutions)) {
            eventLogger.failure("ALERT_EXECUTION_SKIPPED", Map.of(
                    "alertId", alertId,
                    "alertName", alertName,
                    "reason", "ALREADY_RUNNING"
            ));
            return;
        }
        executor.submit(() -> execute(alertId));
    }

    public boolean isRunning(long alertId) {
        AtomicInteger count = activeAlerts.get(alertId);
        return count != null && count.get() > 0;
    }

    private void execute(long alertId) {
        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        WorkerEndpoint endpoint = null;
        String workerName = null;
        String workerInstanceId = null;
        try {
            PreparedAlertExecution execution = preparationService.prepare(alertId).orElse(null);
            if (execution == null)
                return;

            try (WorkerReservation reservation = workerStatusService.reserve(execution.requiredCapability())) {
                SelectedWorker worker = reservation.worker();
                endpoint = worker.endpoint();
                workerName = worker.status().getWorkerName();
                workerInstanceId = worker.status().getWorkerInstanceId();
                eventLogger.success("ALERT_EXECUTION_STARTED", Map.of(
                        "executionId", executionId,
                        "alertId", execution.alertId(),
                        "alertName", execution.alertName(),
                        "worker", endpoint.toString(),
                        "workerName", workerName,
                        "workerInstanceId", workerInstanceId,
                        "workerLoad", worker.currentLoad()
                ));

                ExecuteAlertRequest request = request(executionId.toString(), execution);
                ExecuteAlertResponse response = workerClient.execute(
                        endpoint, request, properties.execution().timeout()
                );
                if (response.hasSourceRequired()) {
                    SynchronizeTemplateResponse synchronization = workerClient.synchronize(
                            endpoint,
                            SynchronizeTemplateRequest.newBuilder()
                                    .setTemplateClassName(execution.templateClassName())
                                    .setSourceChecksum(execution.sourceChecksum())
                                    .setSource(execution.source())
                                    .build(),
                            properties.execution().sourceSynchronizationTimeout()
                    );
                    if (!synchronization.getSynchronized()) {
                        Instant finishedAt = Instant.now();
                        persistenceService.persistRemoteFailure(
                                alertId, executionId, endpoint, workerName, workerInstanceId,
                                startedAt, finishedAt, finishedAt,
                                synchronization.getError()
                        );
                        return;
                    }
                    response = workerClient.execute(endpoint, request, properties.execution().timeout());
                }
                if (!response.hasResult())
                    throw new IllegalStateException("Worker did not return an execution result");

                persistenceService.persistWorkerResult(
                        alertId, executionId, endpoint, response.getResult()
                );
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
                data.put("exceptionType", exception.getClass().getName());
                if (exception.getMessage() != null)
                    data.put("exceptionMessage", exception.getMessage());

                data.put("persistenceExceptionType", persistenceException.getClass().getName());
                eventLogger.error("ALERT_EXECUTION_DISPATCH_FAILED", data);
            }
        } finally {
            leave(alertId);
        }
    }

    private static ExecuteAlertRequest request(
        String executionId,
        PreparedAlertExecution execution
    ) {
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
                    .setNullValue(parameter.nullValue());
            if (!parameter.nullValue())
                value.setValue(parameter.value());

            request.addParameters(value);
        }
        return request.build();
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
