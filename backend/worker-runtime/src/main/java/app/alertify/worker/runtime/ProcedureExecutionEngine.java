package app.alertify.worker.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.worker.grpc.AlertParameterValueSource;
import app.alertify.worker.grpc.ExecuteProcedureRequest;
import app.alertify.worker.grpc.ProcedureExecutionResult;
import io.grpc.Deadline;
import io.grpc.stub.StreamObserver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one procedure template per request on a virtual thread and answers the
 * caller with a single {@link ProcedureExecutionResult}. Unlike alert
 * executions, procedures do not compete for the alert concurrency semaphore:
 * a procedure is normally invoked from an alert or another procedure that is
 * already holding a permit, so gating them again would deadlock the worker.
 * Failures are never propagated to gRPC as an error status; they are reported
 * as an unsuccessful result so the caller can persist and audit them.
 */
class ProcedureExecutionEngine implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcedureExecutionEngine.class);
    private final AlertTemplateCompiler compiler;
    private final WorkerExecutionTracker tracker;
    private final WorkerRuntimeProperties properties;
    private final WorkerInstanceIdentity identity;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    ProcedureExecutionEngine(AlertTemplateCompiler compiler, WorkerExecutionTracker tracker, WorkerRuntimeProperties properties, WorkerInstanceIdentity identity) {
        this.compiler = compiler;
        this.tracker = tracker;
        this.properties = properties;
        this.identity = identity;
    }

    void execute(ExecuteProcedureRequest request, StreamObserver<ProcedureExecutionResult> observer, Deadline deadline, ProcedureHandleFactory handles) {
        Instant startedAt = Instant.now();
        executor.submit(() -> run(request, observer, startedAt, deadline, handles));
    }

    private void run(ExecuteProcedureRequest request, StreamObserver<ProcedureExecutionResult> observer, Instant startedAt, Deadline deadline, ProcedureHandleFactory handles) {
        try (WorkerExecutionTracker.ProcedurePermit permit = tracker.startProcedure(request, Instant.now())) {
            Map<String, AlertParameterSource> sources = request.getParametersList().stream()
                    .collect(Collectors.toUnmodifiableMap(parameter -> parameter.getName(),
                            parameter -> source(parameter.getSource())));
            ProcedureExecutionContext context = new ProcedureExecutionContext(permit.workStartedAt(), sources);
            CompiledProcedureTemplate template = compiler.getProcedure(request.getTemplateClassName(), request.getSourceChecksum());
            ProcedureEvaluator evaluator = template.newInstance(request.getParametersList(), handles, deadline);
            JsonNode result = evaluator.execute(context);
            if (result == null)
                throw new IllegalStateException("Procedure template returned Java null; a JsonNode is required");

            CompiledProcedureTemplate.WritableValues writable = template.writableValues(evaluator, request.getParametersList());
            observer.onNext(ProcedureExecutionResult.newBuilder()
                    .setSuccessful(true)
                    .setStartedAt(WorkerExecutionEngine.timestamp(startedAt))
                    .setWorkStartedAt(WorkerExecutionEngine.timestamp(permit.workStartedAt()))
                    .setFinishedAt(WorkerExecutionEngine.timestamp(Instant.now()))
                    .setResultJson(jsonMapper.writeValueAsString(result))
                    .setWorkerName(properties.name())
                    .setWorkerInstanceId(identity.id())
                    .addAllWritableConfigurationValues(writable.configurationValues())
                    .addAllWritableSecretValues(writable.secretValues())
                    .build());
            observer.onCompleted();
        } catch (Throwable exception) {
            if (exception instanceof InterruptedException)
                Thread.currentThread().interrupt();

            Instant now = Instant.now();
            observer.onNext(ProcedureExecutionResult.newBuilder()
                    .setSuccessful(false)
                    .setStartedAt(WorkerExecutionEngine.timestamp(startedAt))
                    .setWorkStartedAt(WorkerExecutionEngine.timestamp(startedAt))
                    .setFinishedAt(WorkerExecutionEngine.timestamp(now))
                    .setError(WorkerExecutionEngine.error(exception))
                    .setWorkerName(properties.name())
                    .setWorkerInstanceId(identity.id())
                    .build());
            observer.onCompleted();
        } finally {
            LOGGER.info("Procedure execution finished: executionId={}, procedureId={}, procedureName={}", request.getExecutionId(), request.getProcedureId(), request.getProcedureName());
        }
    }

    private static AlertParameterSource source(AlertParameterValueSource source) {
        return switch (source) {
            case ALERT_PARAMETER_VALUE_SOURCE_TEXT -> AlertParameterSource.TEXT;
            case ALERT_PARAMETER_VALUE_SOURCE_CONFIGURATION -> AlertParameterSource.CONFIGURATION;
            case ALERT_PARAMETER_VALUE_SOURCE_SECRET -> AlertParameterSource.SECRET;
            case ALERT_PARAMETER_VALUE_SOURCE_PROCEDURE -> AlertParameterSource.PROCEDURE;
            case ALERT_PARAMETER_VALUE_SOURCE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("Procedure parameter source must be specified");
        };
    }

    @Override
    public void close() { executor.close(); }
}
