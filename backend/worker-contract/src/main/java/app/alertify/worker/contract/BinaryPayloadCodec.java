package app.alertify.worker.contract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Encodes the single-entry ZIP envelope used for binary bindings. */
public final class BinaryPayloadCodec {

    public static final int DEFAULT_MAX_VALUE_BYTES = 100 * 1024 * 1024;
    private static final String ENTRY_NAME = "payload";

    private BinaryPayloadCodec() {
    }

    public static byte[] compress(byte[] value, int maximumBytes) {
        requireSize(value, maximumBytes);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(value.length, 1024 * 1024));
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                ZipEntry entry = new ZipEntry(ENTRY_NAME);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(value);
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Binary value could not be compressed", exception);
        }
    }

    public static byte[] decompress(byte[] archive, int maximumBytes) {
        if (archive == null || archive.length == 0)
            throw new IllegalArgumentException("Binary ZIP must not be empty");

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || !ENTRY_NAME.equals(entry.getName()) || entry.isDirectory())
                throw new IllegalArgumentException("Binary ZIP must contain exactly one payload entry");

            ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity(entry, maximumBytes));
            CRC32 crc = new CRC32();
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = zip.read(buffer)) >= 0;) {
                if (read == 0)
                    continue;
                total = Math.addExact(total, read);
                if (total > maximumBytes)
                    throw new IllegalArgumentException("Binary value exceeds the " + maximumBytes + " byte limit");
                output.write(buffer, 0, read);
                crc.update(buffer, 0, read);
            }
            Arrays.fill(buffer, (byte) 0);
            zip.closeEntry();
            if (zip.getNextEntry() != null)
                throw new IllegalArgumentException("Binary ZIP must contain exactly one payload entry");
            if (entry.getCrc() >= 0 && entry.getCrc() != crc.getValue())
                throw new IllegalArgumentException("Binary ZIP CRC is invalid");
            byte[] value = output.toByteArray();
            requireSize(value, maximumBytes);
            return value;
        } catch (IOException | ArithmeticException exception) {
            throw new IllegalArgumentException("Binary ZIP is invalid", exception);
        }
    }

    private static int initialCapacity(ZipEntry entry, int maximumBytes) {
        long size = entry.getSize();
        return size > 0 && size <= maximumBytes ? (int) size : Math.min(maximumBytes, 1024 * 1024);
    }

    private static void requireSize(byte[] value, int maximumBytes) {
        if (value == null || value.length == 0)
            throw new IllegalArgumentException("Binary value must contain at least one byte");
        if (maximumBytes <= 0 || value.length > maximumBytes)
            throw new IllegalArgumentException("Binary value exceeds the " + maximumBytes + " byte limit");
    }
}
