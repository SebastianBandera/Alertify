package app.alertify.worker.runtime;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.protobuf.Timestamp;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertExecutionValue;
import app.alertify.alerts.AlertExecutionValueSource;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.worker.contract.SecretValueSanitizer;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.AlertParameterValueSource;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.ExecutionError;
import app.alertify.worker.grpc.WorkerExecutionStatus;
import io.grpc.stub.StreamObserver;
import io.grpc.Deadline;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one alert template per request on a virtual thread, bounded by the
 * permits handed out by {@link WorkerExecutionTracker}. Every outcome, success
 * or failure, is reported as an {@link AlertExecutionResult} on the observer so
 * the caller always receives timings, the resulting state and any writable
 * parameter value the template changed.
 */
class WorkerExecutionEngine implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerExecutionEngine.class);

    private final AlertTemplateCompiler compiler;
    private final WorkerExecutionTracker tracker;
    private final WorkerRuntimeProperties properties;
    private final WorkerInstanceIdentity instanceIdentity;
    private final BinaryExecutionGuard binaryExecutionGuard;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    WorkerExecutionEngine(AlertTemplateCompiler compiler, WorkerExecutionTracker tracker, WorkerRuntimeProperties properties, WorkerInstanceIdentity instanceIdentity) {
        this(compiler, tracker, properties, instanceIdentity, new BinaryExecutionGuard());
    }

    @Autowired
    WorkerExecutionEngine(AlertTemplateCompiler compiler, WorkerExecutionTracker tracker, WorkerRuntimeProperties properties, WorkerInstanceIdentity instanceIdentity, BinaryExecutionGuard binaryExecutionGuard) {
        this.compiler = compiler;
        this.tracker = tracker;
        this.properties = properties;
        this.instanceIdentity = instanceIdentity;
        this.binaryExecutionGuard = binaryExecutionGuard;
    }

    void execute(ExecuteAlertRequest request, StreamObserver<AlertExecutionResult> observer) {
        execute(request, observer, null, unavailableProcedureHandles());
    }

    void execute(ExecuteAlertRequest request, StreamObserver<AlertExecutionResult> observer, Deadline deadline, ProcedureHandleFactory procedureHandles) {
        ExecutionTimeline timeline = ExecutionTimeline.start(request.getExecutionId());
        executor.submit(() -> run(request, observer, timeline, deadline, procedureHandles));
    }

    private void run(ExecuteAlertRequest request, StreamObserver<AlertExecutionResult> observer, ExecutionTimeline timeline, Deadline deadline, ProcedureHandleFactory procedureHandles) {
        WorkerExecutionTracker.Permit permit = null;
        BinaryExecutionGuard.Lease binaryLease = null;
        AlertExecutionContext context = null;
        AlertExecutionStatus finalStatus = AlertExecutionStatus.ERROR;
        List<String> secretValues = secretValues(request);
        try {
            permit = tracker.acquire(request, timeline);
            binaryLease = binaryExecutionGuard.acquire(request);
            Map<String, AlertParameterSource> parameterSources = request.getParametersList().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            AlertParameter::getName,
                            parameter -> source(parameter.getSource())
                    ));
            List<AlertExecutionValue> preparedValues = request.getPreparedValuesList().stream()
                    .map(value -> new AlertExecutionValue(valueSource(value.getSource()), value.getName(), value.getValue()))
                    .toList();
            context = new AlertExecutionContext(SecretValueSanitizer.sanitize(request.getState(), secretValues), parameterSources, preparedValues);

            CompiledAlertTemplate template = compiler.get(request.getTemplateClassName(), request.getSourceChecksum());
            AlertEvaluator evaluator = template.newInstance(request.getParametersList(), procedureHandles, deadline);
            AlertResult result = evaluator.evaluate(context);

            finalStatus = result.status();
            Instant finishedAt = timeline.next();
            String statusMessageJson = jsonMapper.writeValueAsString(SecretValueSanitizer.sanitize(
                    jsonMapper.valueToTree(result.statusMessage()), secretValues
            ));

            AlertExecutionResult.Builder response = AlertExecutionResult.newBuilder()
                    .setStatus(toGrpcStatus(result.status()))
                    .setStartedAt(timestamp(timeline.startedAt()))
                    .setWorkStartedAt(timestamp(permit.workStartedAt()))
                    .setFinishedAt(timestamp(finishedAt))
                    .setStatusMessageJson(statusMessageJson)
                    .setState(SecretValueSanitizer.sanitize(context.getState(), secretValues))
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

            Instant workStartedAt = permit == null ? timeline.startedAt() : permit.workStartedAt();
            observer.onNext(AlertExecutionResult.newBuilder()
                    .setStatus(WorkerExecutionStatus.WORKER_EXECUTION_STATUS_ERROR)
                    .setStartedAt(timestamp(timeline.startedAt()))
                    .setWorkStartedAt(timestamp(workStartedAt))
                    .setFinishedAt(timestamp(timeline.next()))
                    .setState(SecretValueSanitizer.sanitize(context == null ? request.getState() : context.getState(), secretValues))
                    .setError(SecretValueSanitizer.sanitize(error(exception), secretValues))
                    .setWorkerName(properties.name())
                    .setWorkerInstanceId(instanceIdentity.id())
                    .build());
            observer.onCompleted();
        } finally {
            if (binaryLease != null)
                binaryLease.close();
            if (permit != null)
                permit.close();

            LOGGER.info("Alert execution finished: executionId={}, alertId={}, alertName={}, status={}", request.getExecutionId(), request.getAlertId(), request.getAlertName(), finalStatus);
        }
    }

    private static ProcedureHandleFactory unavailableProcedureHandles() {
        return new ProcedureHandleFactory((_, _) -> { throw new IllegalStateException("Procedure invocation requires an execution stream"); });
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
            case ALERT_PARAMETER_VALUE_SOURCE_PROCEDURE -> AlertParameterSource.PROCEDURE;
            case ALERT_PARAMETER_VALUE_SOURCE_PIPE -> AlertParameterSource.PIPE;
            case ALERT_PARAMETER_VALUE_SOURCE_PIPE_OUTPUT -> AlertParameterSource.PIPE_OUTPUT;
            case ALERT_PARAMETER_VALUE_SOURCE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("Alert parameter source must be specified");
        };
    }

    private static AlertExecutionValueSource valueSource(app.alertify.worker.grpc.AlertExecutionValueSource source) {
        return switch (source) {
            case ALERT_EXECUTION_VALUE_SOURCE_CONFIGURATION -> AlertExecutionValueSource.CONFIGURATION;
            case ALERT_EXECUTION_VALUE_SOURCE_SECRET -> AlertExecutionValueSource.SECRET;
            case ALERT_EXECUTION_VALUE_SOURCE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("Prepared alert value source must be specified");
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

    private static List<String> secretValues(ExecuteAlertRequest request) {
        List<String> values = new java.util.ArrayList<>(request.getParametersList().stream()
                .filter(parameter -> parameter.getSource() == AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_SECRET)
                .filter(parameter -> !parameter.getNullValue() && !parameter.getValue().isEmpty())
                .map(AlertParameter::getValue)
                .toList());
        request.getPreparedValuesList().stream()
                .filter(value -> value.getSource() == app.alertify.worker.grpc.AlertExecutionValueSource.ALERT_EXECUTION_VALUE_SOURCE_SECRET)
                .map(app.alertify.worker.grpc.PreparedAlertValue::getValue)
                .filter(value -> !value.isEmpty())
                .forEach(values::add);
        return List.copyOf(values);
    }

    @Override
    public void close() {
        executor.close();
    }
}
