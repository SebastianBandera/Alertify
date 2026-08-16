package app.alertify.services.secret;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.api.ConfigurationResponse;
import app.alertify.configuration.service.ApplicationConfigurationLookupService;
import app.alertify.jpa.entity.ConfigurationValueType;

@Component
class DatabaseKeyPartSource {

    private static final String KEY_PART_NAME = "KEY_PART";

    private final ApplicationConfigurationLookupService configurationLookup;

    DatabaseKeyPartSource(ApplicationConfigurationLookupService configurationLookup) {
        this.configurationLookup = configurationLookup;
    }

    byte[] read() {
        final var configuration = readConfiguration();

        if (configuration.valueType() != ConfigurationValueType.STRING
                || !configuration.value().isString()) {
            throw new IllegalStateException(
                "Required configuration '" + KEY_PART_NAME + "' must have type STRING"
            );
        }

        String keyPart = configuration.value().stringValue();
        if (keyPart == null || keyPart.isEmpty()) {
            throw new IllegalStateException(
                "Required configuration '" + KEY_PART_NAME + "' must contain at least one character"
            );
        }
        return keyPart.getBytes(StandardCharsets.UTF_8);
    }

    private ConfigurationResponse readConfiguration() {
        try {
            return configurationLookup.getByName(KEY_PART_NAME);
        } catch (ResourceNotFoundException exception) {
            throw new IllegalStateException(
                "Required configuration '" + KEY_PART_NAME + "' was not found", exception
            );
        }
    }
}
