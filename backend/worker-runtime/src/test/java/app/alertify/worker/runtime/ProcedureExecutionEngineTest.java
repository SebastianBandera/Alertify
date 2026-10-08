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
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.AlertParameterValueSource;
import app.alertify.worker.grpc.ExecuteProcedureRequest;
import app.alertify.worker.grpc.InvokeProcedureResponse;
import app.alertify.worker.grpc.ProcedureExecutionResult;
import app.alertify.worker.grpc.TemplateKind;
import io.grpc.Deadline;
import io.grpc.stub.StreamObserver;

class ProcedureExecutionEngineTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void sanitizesSensitivePipeOutputFromProcedureFailures() throws Exception {
        WorkerRuntimeProperties properties = properties();
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        String source = """
                package dynamic;

                import app.alertify.procedures.ProcedureEvaluator;
                import app.alertify.procedures.ProcedureExecutionContext;
                import tools.jackson.databind.JsonNode;

                public final class SecretFailingProcedure implements ProcedureEvaluator {
                    private final String token;

                    public SecretFailingProcedure(String token) {
                        this.token = token;
                    }

                    @Override
                    public JsonNode execute(ProcedureExecutionContext context) {
                        throw new IllegalStateException("credential=" + token);
                    }
                }
                """;
        String checksum = sha256(source);
        compiler.synchronize("dynamic.SecretFailingProcedure", checksum, source, TemplateKind.TEMPLATE_KIND_PROCEDURE);
        WorkerExecutionTracker tracker = new WorkerExecutionTracker(properties);
        WorkerInstanceIdentity identity = new WorkerInstanceIdentity();
        CompletableFuture<ProcedureExecutionResult> result = new CompletableFuture<>();
        ProcedureHandleFactory handles = new ProcedureHandleFactory((_, _) -> InvokeProcedureResponse.getDefaultInstance());

        try (ProcedureExecutionEngine engine = new ProcedureExecutionEngine(compiler, tracker, properties, identity)) {
            engine.execute(
                    ExecuteProcedureRequest.newBuilder()
                            .setExecutionId("procedure-sensitive-pipe-output-failure")
                            .setProcedureId(7)
                            .setProcedureName("Sensitive Pipe output failure sample")
                            .setTemplateClassName("dynamic.SecretFailingProcedure")
                            .setSourceChecksum(checksum)
                            .addParameters(AlertParameter.newBuilder()
                                    .setName("token")
                                    .setJavaType(String.class.getName())
                                    .setValue("derived-token")
                                    .setSource(AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_PIPE_OUTPUT)
                                    .setSensitive(true))
                            .build(),
                    observer(result), Deadline.after(5, TimeUnit.SECONDS), handles
            );

            ProcedureExecutionResult execution = result.get(5, TimeUnit.SECONDS);
            assertThat(execution.getSuccessful()).isFalse();
            assertThat(execution.getError().getMessage()).isEqualTo("credential=[REDACTED]");
            assertThat(execution.getError().getStackTrace()).doesNotContain("derived-token");
        }
    }

    private WorkerRuntimeProperties properties() {
        return new WorkerRuntimeProperties("test-worker", 0, 134217728, Duration.ofSeconds(1),
                Set.of(WorkerCapability.STANDARD), 1, temporaryDirectory.resolve("compiled"), null,
                new WorkerRuntimeProperties.Tls(false, null, null, null));
    }

    private static StreamObserver<ProcedureExecutionResult> observer(CompletableFuture<ProcedureExecutionResult> result) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ProcedureExecutionResult value) {
                result.complete(value);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onCompleted() {
                // The result is captured by onNext.
            }
        };
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
