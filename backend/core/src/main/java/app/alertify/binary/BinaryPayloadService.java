package app.alertify.binary;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import app.alertify.worker.contract.BinaryPayloadCodec;

@Service
public class BinaryPayloadService {
    private final int maximumBytes;

    public BinaryPayloadService(@Value("${binary.max-value-bytes:104857600}") int maximumBytes) {
        if (maximumBytes <= 0)
            throw new IllegalArgumentException("binary.max-value-bytes must be positive");
        this.maximumBytes = maximumBytes;
    }

    public PreparedBinary prepare(byte[] value, String fileName, String contentType) {
        byte[] zip = BinaryPayloadCodec.compress(value, maximumBytes);
        return new PreparedBinary(safeFileName(fileName), safeContentType(contentType), value.length, zip.length, sha256(value), zip);
    }

    public byte[] decompress(byte[] zip) {
        return BinaryPayloadCodec.decompress(zip, maximumBytes);
    }

    public int maximumBytes() { return maximumBytes; }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeFileName(String name) {
        String normalized = name == null ? "binary.bin" : name.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (normalized.isEmpty()) normalized = "binary.bin";
        return normalized.length() <= 255 ? normalized : normalized.substring(normalized.length() - 255);
    }

    private static String safeContentType(String value) {
        if (value == null || value.isBlank()) return "application/octet-stream";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+") ? normalized : "application/octet-stream";
    }

    public record PreparedBinary(String fileName, String contentType, long size, long zipSize, byte[] sha256, byte[] zip) {

        @Override
        public boolean equals(Object object) {
            if (this == object)
                return true;

            if (!(object instanceof PreparedBinary(
                    var otherFileName,
                    var otherContentType,
                    var otherSize,
                    var otherZipSize,
                    var otherSha256,
                    var otherZip)))
                return false;

            return size == otherSize
                    && zipSize == otherZipSize
                    && Objects.equals(fileName, otherFileName)
                    && Objects.equals(contentType, otherContentType)
                    && Arrays.equals(sha256, otherSha256)
                    && Arrays.equals(zip, otherZip);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(fileName, contentType, size, zipSize);
            result = 31 * result + Arrays.hashCode(sha256);
            return 31 * result + Arrays.hashCode(zip);
        }

        @Override
        public String toString() {
            return "PreparedBinary["
                    + "fileName=" + fileName
                    + ", contentType=" + contentType
                    + ", size=" + size
                    + ", zipSize=" + zipSize
                    + ", sha256Length=" + (sha256 == null ? "null" : sha256.length)
                    + ", zipLength=" + (zip == null ? "null" : zip.length)
                    + "]";
        }
    }
}
