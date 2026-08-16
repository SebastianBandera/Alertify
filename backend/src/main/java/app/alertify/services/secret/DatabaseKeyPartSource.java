package app.alertify.services.secret;

import java.util.HexFormat;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;

@Component
class DatabaseKeyPartSource {

    private static final String KEY_PART_NAME = "KEY_PART";

    private final ApplicationConfigurationRepository repository;

    DatabaseKeyPartSource(ApplicationConfigurationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    byte[] read() {
        var configuration = repository.findByName(KEY_PART_NAME)
            .orElseThrow(() -> new IllegalStateException(
                "Required configuration '" + KEY_PART_NAME + "' was not found"
            ));

        if (configuration.getValueType() != ConfigurationValueType.STRING
                || !configuration.getValue().isString()) {
            throw new IllegalStateException(
                "Required configuration '" + KEY_PART_NAME + "' must have type STRING"
            );
        }

        String keyPart = configuration.getValue().stringValue();
        if (keyPart == null || keyPart.length() != 64) {
            throw new IllegalStateException(
                "Required configuration '" + KEY_PART_NAME + "' must contain 64 hexadecimal characters"
            );
        }

        try {
            return HexFormat.of().parseHex(keyPart);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Required configuration '" + KEY_PART_NAME + "' is not a valid hexadecimal key part",
                exception
            );
        }
    }
}
