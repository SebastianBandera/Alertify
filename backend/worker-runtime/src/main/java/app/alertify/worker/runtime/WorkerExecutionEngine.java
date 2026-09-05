package app.alertify.worker.runtime;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Timestamp;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.AlertParameterValueSource;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.ExecutionError;
import app.alertify.worker.grpc.WorkerExecutionStatus;
import io.grpc.stub.StreamObserver;
import tools.jackson.databind.json.JsonMapper;

class WorkerExecutionEngine implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerExecutionEngine.class);

    private final AlertTemplateCompiler compiler;
    private final WorkerExecutionTracker tracker;
    private final WorkerRuntimeProperties properties;
    private final WorkerInstanceIdentity instanceIdentity;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    WorkerExecutionEngine(AlertTemplateCompiler compiler, WorkerExecutionTracker tracker, WorkerRuntimeProperties properties, WorkerInstanceIdentity instanceIdentity) {
        this.compiler = compiler;
        this.tracker = tracker;
        this.properties = properties;
        this.instanceIdentity = instanceIdentity;
    }

    void execute(ExecuteAlertRequest request, StreamObserver<AlertExecutionResult> observer) {
        Instant queuedAt = Instant.now();
        executor.submit(() -> run(request, observer, queuedAt));
    }

    private void run(ExecuteAlertRequest request, StreamObserver<AlertExecutionResult> observer, Instant startedAt) {
        WorkerExecutionTracker.Permit permit = null;
        AlertExecutionContext context = null;
        AlertExecutionStatus finalStatus = AlertExecutionStatus.ERROR;
        try {
            permit = tracker.acquire(request, startedAt);
            Map<String, AlertParameterSource> parameterSources = request.getParametersList().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            parameter -> parameter.getName(),
                            parameter -> source(parameter.getSource())
                    ));
            context = new AlertExecutionContext(request.getState(), parameterSources);

            CompiledAlertTemplate template = compiler.get(request.getTemplateClassName(), request.getSourceChecksum());
            AlertEvaluator evaluator = template.newInstance(request.getParametersList());
            AlertResult result = evaluator.evaluate(context);

            finalStatus = result.status();
            Instant finishedAt = Instant.now();

            AlertExecutionResult.Builder response = AlertExecutionResult.newBuilder()
                    .setStatus(toGrpcStatus(result.status()))
                    .setStartedAt(timestamp(startedAt))
                    .setWorkStartedAt(timestamp(permit.workStartedAt()))
                    .setFinishedAt(timestamp(finishedAt))
                    .setStatusMessageJson(jsonMapper.writeValueAsString(result.statusMessage()))
                    .setState(context.getState())
                    .setWorkerName(properties.name())
                    .setWorkerInstanceId(instanceIdentity.id());
            CompiledAlertTemplate.WritableValues writableValues = template.writableValues(
                    evaluator, request.getParametersList()
            );
            response.addAllWritableConfigurationValues(writableValues.configurationValues());
            response.addAllWritableSecretValues(writableValues.secretValues());
            observer.onNext(response.build());
            observer.onCompleted();
        } catch (Throwable exception) {
            if (exception instanceof InterruptedException)
                Thread.currentThread().interrupt();

            Instant workStartedAt = permit == null ? startedAt : permit.workStartedAt();
            observer.onNext(AlertExecutionResult.newBuilder()
                    .setStatus(WorkerExecutionStatus.WORKER_EXECUTION_STATUS_ERROR)
                    .setStartedAt(timestamp(startedAt))
                    .setWorkStartedAt(timestamp(workStartedAt))
                    .setFinishedAt(timestamp(Instant.now()))
                    .setState(context == null ? request.getState() : context.getState())
                    .setError(error(exception))
                    .setWorkerName(properties.name())
                    .setWorkerInstanceId(instanceIdentity.id())
                    .build());
            observer.onCompleted();
        } finally {
            if (permit != null)
                permit.close();

            LOGGER.info("Alert execution finished: executionId={}, alertId={}, alertName={}, status={}", request.getExecutionId(), request.getAlertId(), request.getAlertName(), finalStatus);
        }
    }

    private static WorkerExecutionStatus toGrpcStatus(AlertExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> WorkerExecutionStatus.WORKER_EXECUTION_STATUS_SUCCESS;
            case WARN -> WorkerExecutionStatus.WORKER_EXECUTION_STATUS_WARN;
            case ERROR -> WorkerExecutionStatus.WORKER_EXECUTION_STATUS_ERROR;
        };
    }

    private static AlertParameterSource source(AlertParameterValueSource source) {
        return switch (source) {
            case ALERT_PARAMETER_VALUE_SOURCE_TEXT -> AlertParameterSource.TEXT;
            case ALERT_PARAMETER_VALUE_SOURCE_CONFIGURATION -> AlertParameterSource.CONFIGURATION;
            case ALERT_PARAMETER_VALUE_SOURCE_SECRET -> AlertParameterSource.SECRET;
            case ALERT_PARAMETER_VALUE_SOURCE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("Alert parameter source must be specified");
        };
    }

    static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder()
                .setSeconds(value.getEpochSecond())
                .setNanos(value.getNano())
                .build();
    }

    static ExecutionError error(Throwable exception) {
        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));
        return ExecutionError.newBuilder()
                .setType(exception.getClass().getName())
                .setMessage(exception.getMessage() == null ? "" : exception.getMessage())
                .setStackTrace(stackTrace.toString())
                .build();
    }

    @Override
    public void close() {
        executor.close();
    }
}
