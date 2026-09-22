package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.ArtifactDescriptor;

class WorkerArtifactStoreTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void storesOnlyClosedArtifactsWithVerifiedMetadataAndDeletesTheirContent() throws Exception {
        byte[] content = "artifact-content".getBytes(StandardCharsets.UTF_8);
        try (WorkerArtifactStore store = store(1024, 2048, Duration.ofMinutes(1))) {
            WorkerArtifactStore.Writer writer = store.create("backup", "backup.sql", "application/sql", Instant.now().plusSeconds(30));
            writer.write(content);

            assertThatThrownBy(writer::descriptor).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not closed");

            writer.close();
            ArtifactDescriptor descriptor = writer.descriptor();
            assertThat(descriptor.getOutputKey()).isEqualTo("backup");
            assertThat(descriptor.getFileName()).isEqualTo("backup.sql");
            assertThat(descriptor.getMediaType()).isEqualTo("application/sql");
            assertThat(descriptor.getSize()).isEqualTo(content.length);
            assertThat(descriptor.getSha256().toByteArray()).isEqualTo(MessageDigest.getInstance("SHA-256").digest(content));
            try (InputStream stored = store.open(descriptor.getArtifactId())) {
                assertThat(stored).hasBinaryContent(content);
            }
            assertThatThrownBy(writer::close).isInstanceOf(IOException.class)
                    .hasMessageContaining("exactly once");

            store.delete(descriptor.getArtifactId());
            assertThatThrownBy(() -> store.open(descriptor.getArtifactId())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unavailable");
        }
    }

    @Test
    void enforcesPerArtifactAndWorkerQuotasAndReleasesCapacityAfterDeletion() throws Exception {
        try (WorkerArtifactStore store = store(4, 6, Duration.ofMinutes(1))) {
            WorkerArtifactStore.Writer tooLarge = store.create("large", "large.bin", "application/octet-stream", Instant.now().plusSeconds(30));
            assertThatThrownBy(() -> tooLarge.write(new byte[5])).isInstanceOf(IOException.class)
                    .hasMessageContaining("per-artifact quota");
            tooLarge.abort();

            WorkerArtifactStore.Writer first = store.create("first", "first.bin", "application/octet-stream", Instant.now().plusSeconds(30));
            first.write(new byte[4]);
            first.close();
            ArtifactDescriptor firstDescriptor = first.descriptor();

            WorkerArtifactStore.Writer second = store.create("second", "second.bin", "application/octet-stream", Instant.now().plusSeconds(30));
            second.write(new byte[2]);
            second.close();

            WorkerArtifactStore.Writer exhausted = store.create("third", "third.bin", "application/octet-stream", Instant.now().plusSeconds(30));
            assertThatThrownBy(() -> exhausted.write(1)).isInstanceOf(IOException.class)
                    .hasMessageContaining("storage quota");
            exhausted.abort();

            store.delete(firstDescriptor.getArtifactId());
            WorkerArtifactStore.Writer replacement = store.create("replacement", "replacement.bin", "application/octet-stream", Instant.now().plusSeconds(30));
            replacement.write(new byte[4]);
            replacement.close();
            assertThat(replacement.descriptor().getSize()).isEqualTo(4);
        }
    }

    @Test
    void sweepsAnAbandonedOpenWriterAfterItsExpiry() throws Exception {
        Path artifacts = temporaryDirectory.resolve("artifacts");
        try (WorkerArtifactStore store = store(1024, 2048, Duration.ofMillis(5))) {
            WorkerArtifactStore.Writer writer = store.create("backup", "backup.sql", "application/sql", Instant.now().plusMillis(50));
            writer.write("partial".getBytes(StandardCharsets.UTF_8));

            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (fileCount(artifacts) != 0 && System.nanoTime() < deadline)
                Thread.sleep(10);

            assertThat(fileCount(artifacts)).isZero();
            assertThatThrownBy(writer::descriptor).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unavailable");
        }
    }

    private WorkerArtifactStore store(long maxArtifactBytes, long maxTotalBytes, Duration sweepInterval) {
        WorkerRuntimeProperties properties = new WorkerRuntimeProperties("test-worker", 0, 134217728,
                Duration.ofSeconds(1), Set.of(WorkerCapability.STANDARD), 1, temporaryDirectory.resolve("compiled"),
                null, new WorkerRuntimeProperties.ArtifactStorage(temporaryDirectory.resolve("artifacts"),
                        maxArtifactBytes, maxTotalBytes, sweepInterval),
                new WorkerRuntimeProperties.Tls(false, null, null, null));
        return new WorkerArtifactStore(properties);
    }

    private static long fileCount(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }
}
