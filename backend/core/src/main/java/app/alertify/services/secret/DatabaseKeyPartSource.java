package app.alertify.services.secret;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.jpa.repository.SystemConfigurationRepository;

/**
 * Reads the database-owned {@code KEY_PART} system configuration used to
 * derive the symmetric encryption key. The value remains internal and is
 * never mapped to a public response.
 */
@Component
class DatabaseKeyPartSource {

    private static final String KEY_PART_NAME = "KEY_PART";

    private final SystemConfigurationRepository systemConfigurationRepository;

    DatabaseKeyPartSource(SystemConfigurationRepository systemConfigurationRepository) {
        this.systemConfigurationRepository = systemConfigurationRepository;
    }

    byte[] read() {
        final var configuration = readConfiguration();

        if (!configuration.getValue().isString()) {
            throw new IllegalStateException(
                    "Required configuration '" + KEY_PART_NAME + "' must have a string value"
            );
        }

        String keyPart = configuration.getValue().stringValue();
        if (keyPart == null || keyPart.isEmpty()) {
            throw new IllegalStateException(
                    "Required configuration '" + KEY_PART_NAME + "' must contain at least one character"
            );
        }
        return keyPart.getBytes(StandardCharsets.UTF_8);
    }

    private SystemConfiguration readConfiguration() {
        return systemConfigurationRepository.findByNameIgnoreCase(KEY_PART_NAME)
                .orElseThrow(
                        () -> new IllegalStateException(
                                "Required configuration '" + KEY_PART_NAME + "' was not found"
                        )
                );
    }
}
