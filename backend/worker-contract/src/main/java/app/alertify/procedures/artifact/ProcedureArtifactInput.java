package app.alertify.procedures.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.Objects;

/** Read-only, execution-scoped view of a transient artifact. */
public final class ProcedureArtifactInput {
    private final String fileName;
    private final String mediaType;
    private final long size;
    private final byte[] sha256;
    private final InputStreamFactory streams;

    public ProcedureArtifactInput(String fileName, String mediaType, long size, byte[] sha256, InputStreamFactory streams) {
        this.fileName = requireText(fileName, "fileName");
        this.mediaType = requireText(mediaType, "mediaType");
        if (size < 0)
            throw new IllegalArgumentException("size must not be negative");

        this.size = size;
        this.sha256 = Objects.requireNonNull(sha256, "sha256 must not be null").clone();
        if (this.sha256.length != 32)
            throw new IllegalArgumentException("sha256 must contain 32 bytes");

        this.streams = Objects.requireNonNull(streams, "streams must not be null");
    }

    public InputStream openStream() throws IOException {
        return streams.open();
    }

    public String fileName() { return fileName; }
    public String mediaType() { return mediaType; }
    public long size() { return size; }
    public byte[] sha256() { return sha256.clone(); }
    public String sha256Hex() { return HexFormat.of().formatHex(sha256); }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.trim()))
            throw new IllegalArgumentException(name + " must be nonblank and trimmed");

        return value;
    }

    @FunctionalInterface
    public interface InputStreamFactory {
        InputStream open() throws IOException;
    }
}
