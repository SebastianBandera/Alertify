package app.alertify.worker.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;

import app.alertify.procedures.artifact.ProcedureArtifactOutput;
import app.alertify.worker.grpc.ArtifactDescriptor;

final class RuntimeProcedureArtifactOutput implements ProcedureArtifactOutput {
    private final String outputKey;
    private final Instant expiresAt;
    private final WorkerArtifactStore store;
    private WorkerArtifactStore.Writer writer;

    RuntimeProcedureArtifactOutput(String outputKey, Instant expiresAt, WorkerArtifactStore store) {
        this.outputKey = outputKey;
        this.expiresAt = expiresAt;
        this.store = store;
    }

    @Override
    public synchronized OutputStream openStream(String fileName, String mediaType) throws IOException {
        if (writer != null)
            throw new IOException("Procedure artifact output '" + outputKey + "' must be opened exactly once");

        writer = store.create(outputKey, fileName, mediaType, expiresAt);
        return writer;
    }

    synchronized ArtifactDescriptor finish() {
        if (writer == null)
            throw new IllegalStateException("Procedure artifact output '" + outputKey + "' was not opened");

        return writer.descriptor();
    }

    synchronized void abort() {
        if (writer != null)
            writer.abort();
    }
}
