package app.alertify.services.secret;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Supplies the environment-owned part of the symmetric key and rejects an
 * empty value during application startup.
 */
@Component
class EnvironmentKeyPartSource {

    private final String keyPart;

    EnvironmentKeyPartSource(@Value("${security.symmetric-key.environment-part}") String keyPart) {
        if (keyPart.isBlank()) {
            throw new IllegalStateException("The environment symmetric-key part must not be blank");
        }

        this.keyPart = keyPart;
    }

    String read() {
        return keyPart;
    }
}
