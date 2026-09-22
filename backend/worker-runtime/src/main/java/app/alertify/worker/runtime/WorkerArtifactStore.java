package app.alertify.worker.runtime;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import app.alertify.worker.grpc.ArtifactDescriptor;

/** Private, quota-limited storage for transient artifacts owned by this worker. */
class WorkerArtifactStore implements AutoCloseable {
    private final Path directory;
    private final long maxArtifactBytes;
    private final long maxTotalBytes;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Writer> openWriters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("artifact-sweeper").factory());
    private long reservedBytes;

    WorkerArtifactStore(WorkerRuntimeProperties properties) {
        WorkerRuntimeProperties.ArtifactStorage storage = properties.artifactStorage();
        if (storage == null || storage.directory() == null)
            throw new IllegalStateException("alertify.worker.artifact-storage.directory must be configured");
        if (storage.maxArtifactBytes() <= 0 || storage.maxTotalBytes() < storage.maxArtifactBytes())
            throw new IllegalStateException("Worker artifact quotas are invalid");

        directory = storage.directory().toAbsolutePath().normalize();
        maxArtifactBytes = storage.maxArtifactBytes();
        maxTotalBytes = storage.maxTotalBytes();
        try {
            Files.createDirectories(directory);
            secure(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize worker artifact storage", exception);
        }
        long interval = storage.sweepInterval() == null ? TimeUnit.MINUTES.toMillis(5) : storage.sweepInterval().toMillis();
        if (interval <= 0)
            throw new IllegalStateException("Worker artifact sweep interval must be positive");

        sweeper.scheduleWithFixedDelay(this::sweepSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    Writer create(String outputKey, String fileName, String mediaType, Instant expiresAt) throws IOException {
        requireText(outputKey, "outputKey");
        requireText(fileName, "fileName");
        requireText(mediaType, "mediaType");
        if (expiresAt == null || !expiresAt.isAfter(Instant.now()))
            throw new IllegalArgumentException("Artifact expiry must be in the future");

        String id = UUID.randomUUID().toString();
        Path path = directory.resolve(id + ".artifact").normalize();
        if (!path.getParent().equals(directory))
            throw new IllegalStateException("Artifact path escaped its private directory");

        OutputStream file = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        secure(path);
        Writer writer = new Writer(id, outputKey, fileName, mediaType, expiresAt, path, file, digest());
        openWriters.put(id, writer);
        return writer;
    }

    InputStream open(String artifactId) throws IOException {
        Entry entry = required(artifactId);
        if (!entry.closed())
            throw new IllegalStateException("Artifact is still being written");

        return Files.newInputStream(entry.path(), StandardOpenOption.READ);
    }

    ArtifactDescriptor descriptor(String artifactId) {
        return required(artifactId).descriptor();
    }

    synchronized void delete(String artifactId) {
        String id = requireId(artifactId);
        Entry entry = entries.get(id);
        if (entry == null)
            return;

        try {
            Files.deleteIfExists(entry.path());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not delete artifact content", exception);
        }
        if (entries.remove(id, entry))
            release(entry.size());
    }

    private Entry required(String artifactId) {
        Entry result = entries.get(requireId(artifactId));
        if (result == null)
            throw new IllegalArgumentException("Artifact is unavailable");

        return result;
    }

    private static String requireId(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Artifact identifier is invalid", exception);
        }
    }

    private synchronized void reserve(long bytes, long artifactSize) throws IOException {
        if (artifactSize > maxArtifactBytes)
            throw new IOException("Artifact exceeds the per-artifact quota");
        if (reservedBytes + bytes > maxTotalBytes)
            throw new IOException("Worker artifact storage quota is exhausted");

        reservedBytes += bytes;
    }

    private synchronized void release(long bytes) {
        reservedBytes = Math.max(0, reservedBytes - bytes);
    }

    private void sweepSafely() {
        Instant now = Instant.now();
        for (Writer writer : openWriters.values()) {
            if (!writer.expiresAt.isAfter(now))
                writer.abort();
        }
        for (Entry entry : entries.values()) {
            if (!entry.expiresAt().isAfter(now)) {
                try {
                    delete(entry.id());
                } catch (RuntimeException ignored) {
                    // The next sweep retries cleanup.
                }
            }
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void secure(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Files.isDirectory(path)
                    ? java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
                    : java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows development filesystems do not expose POSIX permissions.
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.trim()))
            throw new IllegalArgumentException(name + " must be nonblank and trimmed");
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
        for (Writer writer : java.util.List.copyOf(openWriters.values()))
            writer.abort();
        for (String id : java.util.List.copyOf(entries.keySet())) {
            try {
                delete(id);
            } catch (RuntimeException ignored) {
                // Container teardown is best effort; the ephemeral directory is removed with it.
            }
        }
    }

    final class Writer extends FilterOutputStream {
        private final String id;
        private final String outputKey;
        private final String fileName;
        private final String mediaType;
        private final Instant expiresAt;
        private final Path path;
        private final MessageDigest digest;
        private long size;
        private boolean closed;
        private boolean failed;

        private Writer(String id, String outputKey, String fileName, String mediaType, Instant expiresAt, Path path, OutputStream file, MessageDigest digest) {
            super(new DigestOutputStream(file, digest));
            this.id = id;
            this.outputKey = outputKey;
            this.fileName = fileName;
            this.mediaType = mediaType;
            this.expiresAt = expiresAt;
            this.path = path;
            this.digest = digest;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            reserve(1, size + 1);
            try {
                super.write(value);
                size++;
            } catch (IOException exception) {
                release(1);
                failed = true;
                throw exception;
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length, size + length);
            try {
                out.write(bytes, offset, length);
                size += length;
            } catch (IOException exception) {
                release(length);
                failed = true;
                throw exception;
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed)
                throw new IOException("Artifact output stream must be closed exactly once");

            closed = true;
            try {
                super.close();
                if (failed)
                    throw new IOException("Artifact output failed");

                byte[] checksum = digest.digest();
                ArtifactDescriptor descriptor = ArtifactDescriptor.newBuilder().setOutputKey(outputKey).setArtifactId(id)
                        .setFileName(fileName).setMediaType(mediaType).setSize(size)
                        .setSha256(com.google.protobuf.ByteString.copyFrom(checksum)).build();
                entries.put(id, new Entry(id, path, size, expiresAt, descriptor, true));
            } catch (IOException | RuntimeException exception) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupException) {
                    exception.addSuppressed(cleanupException);
                } finally {
                    release(size);
                }
                throw exception;
            } finally {
                openWriters.remove(id, this);
            }
        }

        ArtifactDescriptor descriptor() {
            if (!closed)
                throw new IllegalStateException("Artifact output stream was not closed");

            return required(id).descriptor();
        }

        synchronized void abort() {
            if (!closed) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
                closed = true;
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
                release(size);
                openWriters.remove(id, this);
            } else {
                delete(id);
            }
        }
    }

    private record Entry(String id, Path path, long size, Instant expiresAt, ArtifactDescriptor descriptor, boolean closed) {
        private Entry {
            descriptor = descriptor.toBuilder().setSha256(com.google.protobuf.ByteString.copyFrom(Arrays.copyOf(descriptor.getSha256().toByteArray(), 32))).build();
        }
    }
}
