package app.alertify.services.secret;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;

@Component
class DatabaseKeyPartSource {

    private static final String KEY_PART_NAME = "KEY_PART";

    private final ApplicationConfigurationRepository configurationRepository;

    DatabaseKeyPartSource(ApplicationConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
    }

    byte[] read() {
        final var configuration = readConfiguration();

        if (configuration.getValueType() != ConfigurationValueType.STRING || !configuration.getValue().isString()) {
            throw new IllegalStateException(
                    "Required configuration '" + KEY_PART_NAME + "' must have type STRING"
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

    private ApplicationConfiguration readConfiguration() {
        return configurationRepository.findByName(KEY_PART_NAME)
                .orElseThrow(
                        () -> new IllegalStateException(
                                "Required configuration '" + KEY_PART_NAME + "' was not found"
                        )
                );
    }
}
