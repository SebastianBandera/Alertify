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
import app.alertify.worker.grpc.AlertExecutionValueSource;
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.AlertParameterValueSource;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.PreparedAlertValue;
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
    void redactsSecretParametersFromReportedExceptions() throws Exception {
        WorkerRuntimeProperties properties = properties();
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        String source = """
                package dynamic;

                import app.alertify.alerts.AlertEvaluator;
                import app.alertify.alerts.AlertExecutionContext;
                import app.alertify.alerts.AlertResult;

                public final class SecretFailingAlert implements AlertEvaluator {
                    private final String token;

                    public SecretFailingAlert(String token) {
                        this.token = token;
                    }

                    @Override
                    public AlertResult evaluate(AlertExecutionContext context) {
                        context.setState("credential=" + token);
                        throw new IllegalStateException("credential=" + token);
                    }
                }
                """;
        String checksum = sha256(source);
        compiler.synchronize("dynamic.SecretFailingAlert", checksum, source);
        CompletableFuture<AlertExecutionResult> result = new CompletableFuture<>();

        try (WorkerExecutionEngine engine = new WorkerExecutionEngine(
                compiler, new WorkerExecutionTracker(properties), properties, new WorkerInstanceIdentity()
        )) {
            engine.execute(
                    ExecuteAlertRequest.newBuilder()
                            .setExecutionId("execution-secret-failure")
                            .setAlertId(10)
                            .setAlertName("Secret failure sample")
                            .setTemplateClassName("dynamic.SecretFailingAlert")
                            .setSourceChecksum(checksum)
                            .addParameters(AlertParameter.newBuilder()
                                    .setName("token")
                                    .setJavaType(String.class.getName())
                                    .setValue("opaque-token")
                                    .setSource(AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_SECRET))
                            .build(),
                    observer(result)
            );

            AlertExecutionResult execution = result.get(5, TimeUnit.SECONDS);

            assertThat(execution.getError().getMessage()).isEqualTo("credential=[REDACTED]");
            assertThat(execution.getError().getStackTrace()).doesNotContain("opaque-token");
            assertThat(execution.getState()).isEqualTo("credential=[REDACTED]");

            CompletableFuture<AlertExecutionResult> derivedResult = new CompletableFuture<>();
            engine.execute(
                    ExecuteAlertRequest.newBuilder()
                            .setExecutionId("execution-sensitive-pipe-output-failure")
                            .setAlertId(10)
                            .setAlertName("Sensitive Pipe output failure sample")
                            .setTemplateClassName("dynamic.SecretFailingAlert")
                            .setSourceChecksum(checksum)
                            .addParameters(AlertParameter.newBuilder()
                                    .setName("token")
                                    .setJavaType(String.class.getName())
                                    .setValue("derived-token")
                                    .setSource(AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_PIPE_OUTPUT)
                                    .setSensitive(true))
                            .build(),
                    observer(derivedResult)
            );

            AlertExecutionResult derived = derivedResult.get(5, TimeUnit.SECONDS);
            assertThat(derived.getError().getMessage()).isEqualTo("credential=[REDACTED]");
            assertThat(derived.getError().getStackTrace()).doesNotContain("derived-token");
            assertThat(derived.getState()).isEqualTo("credential=[REDACTED]");
        }
    }

    @Test
    void redactsSecretParametersFromStatusMessageAndState() throws Exception {
        WorkerRuntimeProperties properties = properties();
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        String source = """
                package dynamic;

                import java.util.List;
                import java.util.Map;
                import app.alertify.alerts.AlertEvaluator;
                import app.alertify.alerts.AlertExecutionContext;
                import app.alertify.alerts.AlertResult;

                public final class SecretResultAlert implements AlertEvaluator {
                    private final String credentials;

                    public SecretResultAlert(String credentials) {
                        this.credentials = credentials;
                    }

                    @Override
                    public AlertResult evaluate(AlertExecutionContext context) {
                        context.setState("previous=" + context.getState() + ";credentials=" + credentials);
                        return AlertResult.warn(Map.of(
                            "detail", "Login monitor at db.internal with opaque-password",
                            "nested", List.of("safe", "db.internal")
                        ));
                    }
                }
                """;
        String checksum = sha256(source);
        compiler.synchronize("dynamic.SecretResultAlert", checksum, source);
        CompletableFuture<AlertExecutionResult> result = new CompletableFuture<>();
        String credentials = "{\"host\":\"db.internal\",\"username\":\"monitor\",\"password\":\"opaque-password\"}";

        try (WorkerExecutionEngine engine = new WorkerExecutionEngine(
                compiler, new WorkerExecutionTracker(properties), properties, new WorkerInstanceIdentity()
        )) {
            engine.execute(
                    ExecuteAlertRequest.newBuilder()
                            .setExecutionId("execution-secret-result")
                            .setAlertId(11)
                            .setAlertName("Secret result sample")
                            .setTemplateClassName("dynamic.SecretResultAlert")
                            .setSourceChecksum(checksum)
                            .setState("initial=db.internal")
                            .addParameters(AlertParameter.newBuilder()
                                    .setName("credentials")
                                    .setJavaType(String.class.getName())
                                    .setValue(credentials)
                                    .setSource(AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_SECRET))
                            .build(),
                    observer(result)
            );

            AlertExecutionResult execution = result.get(5, TimeUnit.SECONDS);

            assertThat(execution.getStatusMessageJson()).doesNotContain("monitor", "db.internal", "opaque-password");
            assertThat(execution.getStatusMessageJson()).contains("[REDACTED]");
            assertThat(execution.getState()).isEqualTo("previous=initial=[REDACTED];credentials=[REDACTED]");
        }
    }

    @Test
    void suppliesAndRedactsOnlyPreparedSecretValues() throws Exception {
        WorkerRuntimeProperties properties = properties();
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        String source = """
                package dynamic;

                import java.util.Map;
                import app.alertify.alerts.AlertEvaluator;
                import app.alertify.alerts.AlertExecutionContext;
                import app.alertify.alerts.AlertExecutionValueSource;
                import app.alertify.alerts.AlertResult;

                public final class PreparedSecretAlert implements AlertEvaluator {
                    public PreparedSecretAlert() {
                    }

                    @Override
                    public AlertResult evaluate(AlertExecutionContext context) {
                        String value = context.requireValue(AlertExecutionValueSource.SECRET, "login password");
                        context.setState("password=" + value);
                        return AlertResult.success(Map.of("password", value));
                    }
                }
                """;
        String checksum = sha256(source);
        compiler.synchronize("dynamic.PreparedSecretAlert", checksum, source);
        CompletableFuture<AlertExecutionResult> result = new CompletableFuture<>();

        try (WorkerExecutionEngine engine = new WorkerExecutionEngine(
                compiler, new WorkerExecutionTracker(properties), properties, new WorkerInstanceIdentity()
        )) {
            engine.execute(
                    ExecuteAlertRequest.newBuilder()
                            .setExecutionId("execution-prepared-secret")
                            .setAlertId(12)
                            .setAlertName("Prepared secret sample")
                            .setTemplateClassName("dynamic.PreparedSecretAlert")
                            .setSourceChecksum(checksum)
                            .setState("previous=prepared-private")
                            .addPreparedValues(PreparedAlertValue.newBuilder()
                                    .setSource(AlertExecutionValueSource.ALERT_EXECUTION_VALUE_SOURCE_SECRET)
                                    .setName("Login Password")
                                    .setValue("prepared-private"))
                            .build(),
                    observer(result)
            );

            AlertExecutionResult execution = result.get(5, TimeUnit.SECONDS);

            assertThat(execution.getStatus()).isEqualTo(WorkerExecutionStatus.WORKER_EXECUTION_STATUS_SUCCESS);
            assertThat(execution.getStatusMessageJson()).isEqualTo("{\"password\":\"[REDACTED]\"}");
            assertThat(execution.getState()).isEqualTo("password=[REDACTED]");
        }
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
                                    .setSource(AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_CONFIGURATION)
                                    .setWritable(true)
                                    .setBindingVersion(7L)
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
                assertThat(value.hasExpectedVersion()).isTrue();
                assertThat(value.getExpectedVersion()).isEqualTo(7L);
            });
        }
    }

    @Test
    void returnsOnlyChangedWritableSecretParameters() throws Exception {
        WorkerRuntimeProperties properties = properties();
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        String source = """
                package dynamic;

                import java.util.Map;
                import app.alertify.alerts.AlertEvaluator;
                import app.alertify.alerts.AlertExecutionContext;
                import app.alertify.alerts.AlertResult;
                import app.alertify.alerts.template.annotation.AlertParameterSource;

                public final class WritableSecretAlert implements AlertEvaluator {
                    private String sourceToken;
                    private String targetToken;

                    public WritableSecretAlert(String sourceToken, String targetToken) {
                        this.sourceToken = sourceToken;
                        this.targetToken = targetToken;
                    }

                    @Override
                    public AlertResult evaluate(AlertExecutionContext context) {
                        targetToken = sourceToken;
                        return AlertResult.success(Map.of(
                            "secretBindingDetected",
                            context.getParameterSource("sourceToken") == AlertParameterSource.SECRET
                        ));
                    }
                }
                """;
        String checksum = sha256(source);
        compiler.synchronize("dynamic.WritableSecretAlert", checksum, source);
        CompletableFuture<AlertExecutionResult> result = new CompletableFuture<>();

        try (WorkerExecutionEngine engine = new WorkerExecutionEngine(
                compiler, new WorkerExecutionTracker(properties), properties,
                new WorkerInstanceIdentity()
        )) {
            engine.execute(
                    ExecuteAlertRequest.newBuilder()
                            .setExecutionId("execution-writable-secret")
                            .setAlertId(9)
                            .setAlertName("Writable secret sample")
                            .setTemplateClassName("dynamic.WritableSecretAlert")
                            .setSourceChecksum(checksum)
                            .addParameters(AlertParameter.newBuilder()
                                    .setName("sourceToken")
                                    .setJavaType(String.class.getName())
                                    .setValue("rotated-token")
                                    .setSource(AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_SECRET))
                            .addParameters(AlertParameter.newBuilder()
                                    .setName("targetToken")
                                    .setJavaType(String.class.getName())
                                    .setValue("previous-token")
                                    .setSource(AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_SECRET)
                                    .setWritable(true)
                                    .setBindingVersion(9L)
                                    .setSecretId(73))
                            .build(),
                    observer(result)
            );

            AlertExecutionResult execution = result.get(5, TimeUnit.SECONDS);

            assertThat(execution.getWritableSecretValuesList()).singleElement().satisfies(value -> {
                assertThat(value.getSecretId()).isEqualTo(73);
                assertThat(value.getParameterName()).isEqualTo("targetToken");
                assertThat(value.getValue()).isEqualTo("rotated-token");
                assertThat(value.getNullValue()).isFalse();
                assertThat(value.hasExpectedVersion()).isTrue();
                assertThat(value.getExpectedVersion()).isEqualTo(9L);
            });
            assertThat(execution.getWritableConfigurationValuesList()).isEmpty();
            assertThat(execution.getStatusMessageJson())
                    .isEqualTo("{\"secretBindingDetected\":true}");
        }
    }

    private WorkerRuntimeProperties properties() {
        return new WorkerRuntimeProperties(
                "test-worker", 0, 134217728, Duration.ofSeconds(1), Set.of(WorkerCapability.STANDARD), 1,
                temporaryDirectory.resolve("compiled"), null,
                new WorkerRuntimeProperties.Tls(false, null, null, null)
        );
    }

    private static StreamObserver<AlertExecutionResult> observer(CompletableFuture<AlertExecutionResult> result) {
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
                // The result future is completed by onNext; no completion signal is needed.
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
