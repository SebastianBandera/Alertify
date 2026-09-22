package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.protobuf.ByteString;

import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.ArtifactChunk;
import app.alertify.worker.grpc.ArtifactDescriptor;
import app.alertify.worker.grpc.ArtifactRequest;
import app.alertify.worker.grpc.ArtifactWriteHeader;
import app.alertify.worker.grpc.ArtifactWriteRequest;
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
        try (WorkerExecutionEngine alertEngine = new WorkerExecutionEngine(compiler, tracker, properties, identity); ProcedureExecutionEngine procedureEngine = new ProcedureExecutionEngine(compiler, tracker, properties, identity); AlertWorkerGrpcService service = new AlertWorkerGrpcService(properties, compiler, tracker, alertEngine, procedureEngine, identity, new WorkerResourceMonitor())) {
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
        try (WorkerExecutionEngine alertEngine = new WorkerExecutionEngine(compiler, tracker, properties, identity); ProcedureExecutionEngine procedureEngine = new ProcedureExecutionEngine(compiler, tracker, properties, identity); AlertWorkerGrpcService service = new AlertWorkerGrpcService(properties, compiler, tracker, alertEngine, procedureEngine, identity, new WorkerResourceMonitor())) {
            StreamObserver<ExecutionClientMessage> input = service.execute(errorObserver(errors));
            input.onNext(ExecutionClientMessage.newBuilder().setTemplateSource(SynchronizeTemplateRequest.getDefaultInstance()).build());

            Throwable error = errors.poll(2, TimeUnit.SECONDS);
            assertThat(error).isInstanceOf(StatusRuntimeException.class);
            assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }
    }

    @Test
    void writesReadsAndDeletesAnArtifactThroughTheGrpcContract() throws Exception {
        WorkerRuntimeProperties properties = properties(1024, 2048);
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        WorkerExecutionTracker tracker = new WorkerExecutionTracker(properties);
        WorkerInstanceIdentity identity = new WorkerInstanceIdentity();
        WorkerArtifactStore store = new WorkerArtifactStore(properties);
        byte[] content = "artifact-content".getBytes(StandardCharsets.UTF_8);
        LinkedBlockingQueue<ArtifactDescriptor> descriptors = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<Throwable> writeErrors = new LinkedBlockingQueue<>();
        CountDownLatch writeCompleted = new CountDownLatch(1);
        try (store; WorkerExecutionEngine alertEngine = new WorkerExecutionEngine(compiler, tracker, properties, identity); ProcedureExecutionEngine procedureEngine = new ProcedureExecutionEngine(compiler, tracker, properties, identity, new BinaryExecutionGuard(), store); AlertWorkerGrpcService service = new AlertWorkerGrpcService(properties, compiler, tracker, alertEngine, procedureEngine, identity, new WorkerResourceMonitor(), store)) {
            StreamObserver<ArtifactWriteRequest> input = service.writeArtifact(capturingObserver(descriptors, writeErrors, writeCompleted));
            input.onNext(ArtifactWriteRequest.newBuilder().setHeader(header(content.length, checksum(content))).build());
            input.onNext(ArtifactWriteRequest.newBuilder().setChunk(ArtifactChunk.newBuilder().setData(ByteString.copyFrom(content))).build());
            input.onCompleted();

            assertThat(writeCompleted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(writeErrors).isEmpty();
            ArtifactDescriptor descriptor = descriptors.poll(2, TimeUnit.SECONDS);
            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSize()).isEqualTo(content.length);
            assertThat(descriptor.getSha256()).isEqualTo(checksum(content));

            LinkedBlockingQueue<ArtifactChunk> chunks = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<Throwable> readErrors = new LinkedBlockingQueue<>();
            CountDownLatch readCompleted = new CountDownLatch(1);
            service.readArtifact(ArtifactRequest.newBuilder().setArtifactId(descriptor.getArtifactId()).build(), capturingObserver(chunks, readErrors, readCompleted));

            assertThat(readCompleted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(readErrors).isEmpty();
            assertThat(chunks.stream().map(ArtifactChunk::getData).reduce(ByteString.EMPTY, ByteString::concat).toByteArray()).isEqualTo(content);

            CountDownLatch deleteCompleted = new CountDownLatch(1);
            service.deleteArtifact(ArtifactRequest.newBuilder().setArtifactId(descriptor.getArtifactId()).build(), capturingObserver(new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>(), deleteCompleted));
            assertThat(deleteCompleted.await(2, TimeUnit.SECONDS)).isTrue();

            LinkedBlockingQueue<Throwable> missingErrors = new LinkedBlockingQueue<>();
            service.readArtifact(ArtifactRequest.newBuilder().setArtifactId(descriptor.getArtifactId()).build(), capturingObserver(new LinkedBlockingQueue<>(), missingErrors, new CountDownLatch(1)));
            assertThat(Status.fromThrowable(missingErrors.poll(2, TimeUnit.SECONDS)).getCode()).isEqualTo(Status.Code.NOT_FOUND);
        }
    }

    @Test
    void rejectsTransferredArtifactsWithAnInvalidChecksumAndCleansTheirContent() throws Exception {
        WorkerRuntimeProperties properties = properties(1024, 2048);
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        WorkerExecutionTracker tracker = new WorkerExecutionTracker(properties);
        WorkerInstanceIdentity identity = new WorkerInstanceIdentity();
        WorkerArtifactStore store = new WorkerArtifactStore(properties);
        byte[] content = "corrupted".getBytes(StandardCharsets.UTF_8);
        LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        try (store; WorkerExecutionEngine alertEngine = new WorkerExecutionEngine(compiler, tracker, properties, identity); ProcedureExecutionEngine procedureEngine = new ProcedureExecutionEngine(compiler, tracker, properties, identity, new BinaryExecutionGuard(), store); AlertWorkerGrpcService service = new AlertWorkerGrpcService(properties, compiler, tracker, alertEngine, procedureEngine, identity, new WorkerResourceMonitor(), store)) {
            StreamObserver<ArtifactWriteRequest> input = service.writeArtifact(capturingObserver(new LinkedBlockingQueue<>(), errors, new CountDownLatch(1)));
            input.onNext(ArtifactWriteRequest.newBuilder().setHeader(header(content.length, ByteString.copyFrom(new byte[32]))).build());
            input.onNext(ArtifactWriteRequest.newBuilder().setChunk(ArtifactChunk.newBuilder().setData(ByteString.copyFrom(content))).build());
            input.onCompleted();

            assertThat(Status.fromThrowable(errors.poll(2, TimeUnit.SECONDS)).getCode()).isEqualTo(Status.Code.DATA_LOSS);
            try (var files = Files.list(properties.artifactStorage().directory())) {
                assertThat(files).isEmpty();
            }
        }
    }

    @Test
    void rejectsTransferredArtifactsThatExceedTheWorkerQuota() throws Exception {
        WorkerRuntimeProperties properties = properties(4, 8);
        AlertTemplateCompiler compiler = new AlertTemplateCompiler(properties);
        WorkerExecutionTracker tracker = new WorkerExecutionTracker(properties);
        WorkerInstanceIdentity identity = new WorkerInstanceIdentity();
        WorkerArtifactStore store = new WorkerArtifactStore(properties);
        byte[] content = "12345".getBytes(StandardCharsets.UTF_8);
        LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        try (store; WorkerExecutionEngine alertEngine = new WorkerExecutionEngine(compiler, tracker, properties, identity); ProcedureExecutionEngine procedureEngine = new ProcedureExecutionEngine(compiler, tracker, properties, identity, new BinaryExecutionGuard(), store); AlertWorkerGrpcService service = new AlertWorkerGrpcService(properties, compiler, tracker, alertEngine, procedureEngine, identity, new WorkerResourceMonitor(), store)) {
            StreamObserver<ArtifactWriteRequest> input = service.writeArtifact(capturingObserver(new LinkedBlockingQueue<>(), errors, new CountDownLatch(1)));
            input.onNext(ArtifactWriteRequest.newBuilder().setHeader(header(content.length, checksum(content))).build());
            input.onNext(ArtifactWriteRequest.newBuilder().setChunk(ArtifactChunk.newBuilder().setData(ByteString.copyFrom(content))).build());

            assertThat(Status.fromThrowable(errors.poll(2, TimeUnit.SECONDS)).getCode()).isEqualTo(Status.Code.DATA_LOSS);
        }
    }

    private WorkerRuntimeProperties properties() {
        return new WorkerRuntimeProperties("test-worker", 0, 134217728, Duration.ofSeconds(1), Set.of(WorkerCapability.STANDARD), 1, temporaryDirectory.resolve("compiled"), null, new WorkerRuntimeProperties.Tls(false, null, null, null));
    }

    private WorkerRuntimeProperties properties(long maxArtifactBytes, long maxTotalBytes) {
        return new WorkerRuntimeProperties("test-worker", 0, 134217728, Duration.ofSeconds(1), Set.of(WorkerCapability.STANDARD), 1, temporaryDirectory.resolve("compiled"), null, new WorkerRuntimeProperties.ArtifactStorage(temporaryDirectory.resolve("artifacts-" + maxArtifactBytes), maxArtifactBytes, maxTotalBytes, Duration.ofMinutes(1)), new WorkerRuntimeProperties.Tls(false, null, null, null));
    }

    private static ArtifactWriteHeader header(long expectedSize, ByteString expectedSha256) {
        return ArtifactWriteHeader.newBuilder().setOutputKey("backup").setFileName("backup.sql").setMediaType("application/sql").setExpectedSize(expectedSize).setExpectedSha256(expectedSha256).setExpiresAt(Instant.now().plusSeconds(30).toString()).build();
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
                // Stream completion is not part of this test's assertion contract.
            }
        };
    }

    private static StreamObserver<ExecutionWorkerMessage> errorObserver(LinkedBlockingQueue<Throwable> errors) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ExecutionWorkerMessage value) {
                // This observer only captures the terminal error expected by the test.
            }

            @Override
            public void onError(Throwable throwable) {
                errors.add(throwable);
            }

            @Override
            public void onCompleted() {
                // Normal completion is irrelevant because this test expects onError.
            }
        };
    }

    private static <T> StreamObserver<T> capturingObserver(LinkedBlockingQueue<T> values, LinkedBlockingQueue<Throwable> errors, CountDownLatch completed) {
        return new StreamObserver<>() {
            @Override
            public void onNext(T value) {
                values.add(value);
            }

            @Override
            public void onError(Throwable throwable) {
                errors.add(throwable);
            }

            @Override
            public void onCompleted() {
                completed.countDown();
            }
        };
    }

    private static ByteString checksum(byte[] value) throws Exception {
        return ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
