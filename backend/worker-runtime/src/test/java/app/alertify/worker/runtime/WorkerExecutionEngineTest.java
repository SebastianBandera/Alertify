package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.WorkerExecutionStatus;
import io.grpc.stub.StreamObserver;

class WorkerExecutionEngineTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void reportsExceptionsAndPreservesStateChangedBeforeTheFailure() throws Exception {
        WorkerRuntimeProperties properties = properties();
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        String source = """
                package dynamic;

                import app.alertify.alerts.AlertEvaluator;
                import app.alertify.alerts.AlertExecutionContext;
                import app.alertify.alerts.AlertResult;

                public final class FailingAlert implements AlertEvaluator {
                    public FailingAlert() {
                    }

                    @Override
                    public AlertResult evaluate(AlertExecutionContext context) {
                        context.setState("updated-before-error");
                        throw new IllegalStateException("expected failure");
                    }
                }
                """;
        String checksum = sha256(source);
        compiler.synchronize("dynamic.FailingAlert", checksum, source);
        WorkerExecutionTracker tracker = new WorkerExecutionTracker(properties);
        WorkerInstanceIdentity identity = new WorkerInstanceIdentity();
        CompletableFuture<AlertExecutionResult> result = new CompletableFuture<>();

        try (WorkerExecutionEngine engine = new WorkerExecutionEngine(
                compiler, tracker, properties, identity
        )) {
            engine.execute(
                    ExecuteAlertRequest.newBuilder()
                            .setExecutionId("execution-1")
                            .setAlertId(7)
                            .setAlertName("Failure sample")
                            .setTemplateClassName("dynamic.FailingAlert")
                            .setSourceChecksum(checksum)
                            .setState("initial")
                            .build(),
                    observer(result)
            );

            AlertExecutionResult execution = result.get(5, TimeUnit.SECONDS);

            assertThat(execution.getStatus())
                    .isEqualTo(WorkerExecutionStatus.WORKER_EXECUTION_STATUS_ERROR);
            assertThat(execution.getState()).isEqualTo("updated-before-error");
            assertThat(execution.getError().getType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(execution.getError().getMessage()).isEqualTo("expected failure");
            assertThat(execution.getWorkerName()).isEqualTo("test-worker");
            assertThat(execution.getWorkerInstanceId()).isEqualTo(identity.id());
            assertThat(execution.getWorkStartedAt().getSeconds())
                    .isGreaterThanOrEqualTo(execution.getStartedAt().getSeconds());
            assertThat(execution.getFinishedAt().getSeconds())
                    .isGreaterThanOrEqualTo(execution.getWorkStartedAt().getSeconds());
        }

        assertThat(tracker.totalExecuted()).isEqualTo(1);
        assertThat(tracker.runningTasks()).isEmpty();
        assertThat(tracker.waitingTasks()).isEmpty();
    }

    @Test
    void returnsOnlyChangedWritableConfigurationParameters() throws Exception {
        WorkerRuntimeProperties properties = properties();
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        String source = """
                package dynamic;

                import java.util.Map;
                import app.alertify.alerts.AlertEvaluator;
                import app.alertify.alerts.AlertExecutionContext;
                import app.alertify.alerts.AlertResult;

                public final class WritableAlert implements AlertEvaluator {
                    private Integer counter;

                    public WritableAlert(Integer counter) {
                        this.counter = counter;
                    }

                    @Override
                    public AlertResult evaluate(AlertExecutionContext context) {
                        counter++;
                        return AlertResult.success(Map.of());
                    }
                }
                """;
        String checksum = sha256(source);
        compiler.synchronize("dynamic.WritableAlert", checksum, source);
        CompletableFuture<AlertExecutionResult> result = new CompletableFuture<>();

        try (WorkerExecutionEngine engine = new WorkerExecutionEngine(
                compiler, new WorkerExecutionTracker(properties), properties,
                new WorkerInstanceIdentity()
        )) {
            engine.execute(
                    ExecuteAlertRequest.newBuilder()
                            .setExecutionId("execution-writable")
                            .setAlertId(8)
                            .setAlertName("Writable sample")
                            .setTemplateClassName("dynamic.WritableAlert")
                            .setSourceChecksum(checksum)
                            .addParameters(AlertParameter.newBuilder()
                                    .setName("counter")
                                    .setJavaType(Integer.class.getName())
                                    .setValue("5")
                                    .setWritable(true)
                                    .setConfigurationId(42))
                            .build(),
                    observer(result)
            );

            AlertExecutionResult execution = result.get(5, TimeUnit.SECONDS);

            assertThat(execution.getWritableConfigurationValuesList()).singleElement().satisfies(value -> {
                assertThat(value.getConfigurationId()).isEqualTo(42);
                assertThat(value.getParameterName()).isEqualTo("counter");
                assertThat(value.getValue()).isEqualTo("6");
                assertThat(value.getNullValue()).isFalse();
            });
        }
    }

    private WorkerRuntimeProperties properties() {
        return new WorkerRuntimeProperties(
                "test-worker", 0, Duration.ofSeconds(1), Set.of(WorkerCapability.STANDARD), 1,
                temporaryDirectory.resolve("compiled"), null,
                new WorkerRuntimeProperties.Tls(false, null, null, null)
        );
    }

    private static StreamObserver<AlertExecutionResult> observer(
        CompletableFuture<AlertExecutionResult> result
    ) {
        return new StreamObserver<>() {
            @Override
            public void onNext(AlertExecutionResult value) {
                result.complete(value);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }
}
