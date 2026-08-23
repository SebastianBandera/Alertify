package app.alertify.services.secret;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.logging.ApplicationEventLogger;

/**
 * Internal read boundary for consumers that need secret metadata or a
 * decrypted value by name. Every catalog or value access is recorded in the
 * application log, while public controllers never expose the value.
 */
@Service
public class SecretAccessService {

    private final ApplicationSecretRepository secretRepository;
    private final SecretEncryptionService encryptionService;
    private final ApplicationEventLogger eventLogger;

    public SecretAccessService(ApplicationSecretRepository secretRepository, SecretEncryptionService encryptionService, ApplicationEventLogger eventLogger) {
        this.secretRepository = secretRepository;
        this.encryptionService = encryptionService;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public List<SecretDescriptor> getAllDescriptors() {
        List<SecretDescriptor> descriptors = secretRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(secret -> new SecretDescriptor(secret.getName(), secret.getDescription()))
                .toList();
        eventLogger.success(
                "SECRET_CATALOG_ACCESSED",
                Map.of("secretCount", descriptors.size())
        );
        return descriptors;
    }

    @Transactional(readOnly = true)
    public String getValueByName(String name) {
        ApplicationSecret secret = secretRepository.findByNameIgnoreCase(name)
                .orElse(null);
        if (secret == null) {
            eventLogger.failure(
                    "SECRET_VALUE_ACCESSED",
                    Map.of("name", name, "reason", "NOT_FOUND")
            );
            throw new ResourceNotFoundException("Secret '" + name + "' was not found");
        }

        try {
            String value = encryptionService.decrypt(secret);
            eventLogger.success(
                    "SECRET_VALUE_ACCESSED",
                    Map.of("secretId", secret.getId(), "name", secret.getName())
            );
            return value;
        } catch (SecretNotRecoverableException exception) {
            eventLogger.failure(
                    "SECRET_VALUE_ACCESSED",
                    Map.of(
                            "secretId", secret.getId(), "name", secret.getName(),
                            "reason", "UNRECOVERABLE"
                    )
            );
            throw exception;
        }
    }
}
