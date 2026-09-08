package app.alertify.secretsexport;

import java.time.Instant;
import java.util.List;

/**
 * On-disk format written into the encrypted export archive. Secret values are
 * plaintext here; the archive's AES encryption is the only protection once
 * this leaves the JVM.
 */
record SecretExportPayload(Instant exportedAt, List<Entry> secrets) {

    record Entry(String name, String description, String value, boolean writable, List<TagExport> tags) {
    }

    record TagExport(String name, String color) {
    }
}
