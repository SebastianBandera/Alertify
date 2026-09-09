package app.alertify.secretsexport;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * On-disk format for the {@code system-configurations.json} entry in the
 * export archive. Values here are plaintext, same as {@link SecretExportPayload};
 * the archive's AES encryption is the only protection once this leaves the JVM.
 */
record SystemConfigurationExportPayload(Instant exportedAt, List<Entry> systemConfigurations) {

    record Entry(String name, String description, JsonNode value, boolean valueHidden) {
    }
}
