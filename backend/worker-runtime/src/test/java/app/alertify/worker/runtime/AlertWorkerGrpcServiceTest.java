package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.ExecutionClientMessage;
import app.alertify.worker.grpc.ExecutionWorkerMessage;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.TemplateKind;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

class AlertWorkerGrpcServiceTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void synchronizesSourceAndCompletesTheExecutionOnTheSameStream() throws Exception {
        WorkerRuntimeProperties properties = properties();
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        WorkerExecutionTracker tracker = new WorkerExecutionTracker(properties);
        WorkerInstanceIdentity identity = new WorkerInstanceIdentity();
        String source = """
                package dynamic;

                import java.util.Map;
                import app.alertify.alerts.AlertEvaluator;
                import app.alertify.alerts.AlertExecutionContext;
                import app.alertify.alerts.AlertResult;

                public final class StreamAlert implements AlertEvaluator {
                    public AlertResult evaluate(AlertExecutionContext context) {
                        return AlertResult.success(Map.of("stream", "bidirectional"));
                    }
                }
                """;
        String checksum = sha256(source);
        LinkedBlockingQueue<ExecutionWorkerMessage> output = new LinkedBlockingQueue<>();
        try (WorkerExecutionEngine alertEngine = new WorkerExecutionEngine(compiler, tracker, properties, identity); ProcedureExecutionEngine procedureEngine = new ProcedureExecutionEngine(compiler, tracker, properties, identity); AlertWorkerGrpcService service = new AlertWorkerGrpcService(properties, compiler, tracker, alertEngine, procedureEngine, identity)) {
            StreamObserver<ExecutionClientMessage> input = service.execute(observer(output));
            input.onNext(ExecutionClientMessage.newBuilder().setStartAlert(ExecuteAlertRequest.newBuilder().setExecutionId("stream-alert").setAlertId(1).setAlertName("Stream alert").setTemplateClassName("dynamic.StreamAlert").setSourceChecksum(checksum)).build());

            assertThat(output.poll(2, TimeUnit.SECONDS).hasSourceRequired()).isTrue();
            input.onNext(ExecutionClientMessage.newBuilder().setTemplateSource(SynchronizeTemplateRequest.newBuilder().setTemplateClassName("dynamic.StreamAlert").setSourceChecksum(checksum).setSource(source).setTemplateKind(TemplateKind.TEMPLATE_KIND_ALERT)).build());

            assertThat(output.poll(5, TimeUnit.SECONDS).getTemplateSynchronization().getSynchronized()).isTrue();
            ExecutionWorkerMessage result = output.poll(5, TimeUnit.SECONDS);
            assertThat(result.hasAlertResult()).isTrue();
            assertThat(result.getAlertResult().getStatusMessageJson()).isEqualTo("{\"stream\":\"bidirectional\"}");
            while (tracker.totalExecuted() == 0)
                Thread.sleep(5);

            assertThat(tracker.totalExecuted()).isEqualTo(1);
        }
    }

    @Test
    void rejectsMessagesThatArriveBeforeTheSingleStartMessage() throws Exception {
        WorkerRuntimeProperties properties = properties();
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        WorkerExecutionTracker tracker = new WorkerExecutionTracker(properties);
        WorkerInstanceIdentity identity = new WorkerInstanceIdentity();
        LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        try (WorkerExecutionEngine alertEngine = new WorkerExecutionEngine(compiler, tracker, properties, identity); ProcedureExecutionEngine procedureEngine = new ProcedureExecutionEngine(compiler, tracker, properties, identity); AlertWorkerGrpcService service = new AlertWorkerGrpcService(properties, compiler, tracker, alertEngine, procedureEngine, identity)) {
            StreamObserver<ExecutionClientMessage> input = service.execute(errorObserver(errors));
            input.onNext(ExecutionClientMessage.newBuilder().setTemplateSource(SynchronizeTemplateRequest.getDefaultInstance()).build());

            Throwable error = errors.poll(2, TimeUnit.SECONDS);
            assertThat(error).isInstanceOf(StatusRuntimeException.class);
            assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }
    }

    private WorkerRuntimeProperties properties() {
        return new WorkerRuntimeProperties("test-worker", 0, Duration.ofSeconds(1), Set.of(WorkerCapability.STANDARD), 1, temporaryDirectory.resolve("compiled"), null, new WorkerRuntimeProperties.Tls(false, null, null, null));
    }

    private static StreamObserver<ExecutionWorkerMessage> observer(LinkedBlockingQueue<ExecutionWorkerMessage> output) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ExecutionWorkerMessage value) {
                output.add(value);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    private static StreamObserver<ExecutionWorkerMessage> errorObserver(LinkedBlockingQueue<Throwable> errors) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ExecutionWorkerMessage value) {
            }

            @Override
            public void onError(Throwable throwable) {
                errors.add(throwable);
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
