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
 * resolved value by name. Expression secrets are evaluated on every access.
 * Every catalog or value access is recorded in the application log, while
 * public controllers never expose the value.
 */
@Service
public class SecretAccessService {

    private final ApplicationSecretRepository secretRepository;
    private final SecretExpressionService expressionService;
    private final ApplicationEventLogger eventLogger;

    public SecretAccessService(ApplicationSecretRepository secretRepository, SecretExpressionService expressionService, ApplicationEventLogger eventLogger) {
        this.secretRepository = secretRepository;
        this.expressionService = expressionService;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public List<SecretDescriptor> getAllDescriptors() {
        List<SecretDescriptor> descriptors = secretRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(secret -> new SecretDescriptor(secret.getName(), secret.getDescription()))
                .toList();
        eventLogger.success("SECRET_CATALOG_ACCESSED", Map.of("secretCount", descriptors.size()));
        return descriptors;
    }

    @Transactional(readOnly = true)
    public String getValueByName(String name) {
        ApplicationSecret secret = secretRepository.findByNameIgnoreCase(name)
                .orElse(null);
        if (secret == null) {
            eventLogger.failure("SECRET_VALUE_ACCESSED", Map.of("name", name, "reason", "NOT_FOUND"));
            throw new ResourceNotFoundException("Secret '" + name + "' was not found");
        }

        try {
            String value = expressionService.resolve(secret);
            eventLogger.success("SECRET_VALUE_ACCESSED", Map.of("secretId", secret.getId(), "name", secret.getName(), "valueType", secret.getValueType()));
            return value;
        } catch (SecretNotRecoverableException exception) {
            eventLogger.failure("SECRET_VALUE_ACCESSED", Map.of("secretId", secret.getId(), "name", secret.getName(), "reason", "UNRECOVERABLE"));
            throw exception;
        } catch (RuntimeException exception) {
            eventLogger.failure("SECRET_VALUE_ACCESSED", Map.of("secretId", secret.getId(), "name", secret.getName(), "reason", "EXPRESSION_FAILED", "message", String.valueOf(exception.getMessage())));
            throw exception;
        }
    }
}
