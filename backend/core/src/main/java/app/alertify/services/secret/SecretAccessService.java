package app.alertify.services.secret;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.SecretBinaryValueRepository;
import app.alertify.logging.ApplicationEventLogger;

/**
 * Internal read boundary for consumers that need secret metadata or a
 * resolved value by name. Expression secrets are evaluated on every access.
 * Every catalog or value access is recorded in the application log, while
 * public controllers never expose the value.
 */
@Service
public class SecretAccessService {

    private static final String REASON = "reason";
    private static final String SECRET_ID = "secretId";
    private static final String SECRET_VALUE_ACCESSED = "SECRET_VALUE_ACCESSED";

    private final ApplicationSecretRepository secretRepository;
    private final SecretBinaryValueRepository binaryValueRepository;
    private final SecretExpressionService expressionService;
    private final SecretEncryptionService encryptionService;
    private final ApplicationEventLogger eventLogger;

    public SecretAccessService(ApplicationSecretRepository secretRepository, SecretBinaryValueRepository binaryValueRepository, SecretExpressionService expressionService, SecretEncryptionService encryptionService, ApplicationEventLogger eventLogger) {
        this.secretRepository = secretRepository;
        this.binaryValueRepository = binaryValueRepository;
        this.expressionService = expressionService;
        this.encryptionService = encryptionService;
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
    public String getValueByName(String name, SecretAccessContext context) {
        ApplicationSecret secret = secretRepository.findByNameIgnoreCase(name)
                .orElse(null);
        if (secret == null) {
            eventLogger.failure(SECRET_VALUE_ACCESSED, data(name, "NOT_FOUND", context));
            throw new ResourceNotFoundException("Secret '" + name + "' was not found");
        }

        return getValue(secret, context);
    }

    @Transactional(readOnly = true)
    public String getValue(ApplicationSecret secret, SecretAccessContext context) {
        try {
            String value = expressionService.resolve(secret, context);
            eventLogger.success(SECRET_VALUE_ACCESSED, data(secret, context));
            return value;
        } catch (SecretNotRecoverableException exception) {
            eventLogger.failure(SECRET_VALUE_ACCESSED, data(secret, "UNRECOVERABLE", context));
            throw exception;
        } catch (RuntimeException exception) {
            Map<String, Object> data = data(secret, "EXPRESSION_FAILED", context);
            data.put("message", String.valueOf(exception.getMessage()));
            eventLogger.failure(SECRET_VALUE_ACCESSED, data);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public byte[] getBinaryValue(ApplicationSecret secret, SecretAccessContext context) {
        try {
            byte[] value = encryptionService.decryptBinary(binaryValueRepository.findById(secret.getId())
                    .orElseThrow(() -> new IllegalStateException("Binary secret payload is missing")));
            eventLogger.success(SECRET_VALUE_ACCESSED, data(secret, context));
            return value;
        } catch (SecretNotRecoverableException exception) {
            eventLogger.failure(SECRET_VALUE_ACCESSED, data(secret, "UNRECOVERABLE", context));
            throw exception;
        } catch (RuntimeException exception) {
            Map<String, Object> data = data(secret, "BINARY_ACCESS_FAILED", context);
            data.put("message", String.valueOf(exception.getMessage()));
            eventLogger.failure(SECRET_VALUE_ACCESSED, data);
            throw exception;
        }
    }

    private static Map<String, Object> data(ApplicationSecret secret, SecretAccessContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(SECRET_ID, secret.getId());
        data.put("name", secret.getName());
        data.put("valueType", secret.getValueType().name());
        context.addTo(data);
        return data;
    }

    private static Map<String, Object> data(ApplicationSecret secret, String reason, SecretAccessContext context) {
        Map<String, Object> data = data(secret, context);
        data.put(REASON, reason);
        return data;
    }

    private static Map<String, Object> data(String name, String reason, SecretAccessContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put(REASON, reason);
        context.addTo(data);
        return data;
    }
}
