package app.alertify.secretsexport;

import java.time.Instant;
import java.util.List;

import app.alertify.jpa.entity.SecretValueType;

/**
 * On-disk format written into the encrypted export archive. Secret values are
 * plaintext here; the archive's AES encryption is the only protection once
 * this leaves the JVM.
 */
record SecretExportPayload(Instant exportedAt, List<Entry> secrets) {

    record Entry(String name, String description, SecretValueType valueType, String value, boolean writable, List<TagExport> tags) {

        /** Archives written before secret types existed carry no {@code valueType}; they are plain strings. */
        SecretValueType valueTypeOrDefault() {
            return valueType == null ? SecretValueType.STRING : valueType;
        }
    }

    record TagExport(String name, String color) {
    }
}
