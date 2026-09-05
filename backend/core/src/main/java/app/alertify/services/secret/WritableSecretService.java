package app.alertify.services.secret;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.grpc.WritableSecretValue;

/**
 * Encrypts and applies changed alert parameter values only to secrets that
 * remain writable when the execution completes.
 */
@Service
public class WritableSecretService {

    private final ApplicationSecretRepository secretRepository;
    private final SecretEncryptionService encryptionService;
    private final ApplicationEventLogger eventLogger;

    public WritableSecretService(ApplicationSecretRepository secretRepository, SecretEncryptionService encryptionService, ApplicationEventLogger eventLogger) {
        this.secretRepository = secretRepository;
        this.encryptionService = encryptionService;
        this.eventLogger = eventLogger;
    }

    public void apply(long alertId, String alertName, UUID executionId, Iterable<WritableSecretValue> values) {
        for (WritableSecretValue value : values)
            applyOne(alertId, alertName, executionId, value);
    }

    private void applyOne(long alertId, String alertName, UUID executionId, WritableSecretValue result) {
        ApplicationSecret secret = secretRepository.findById(result.getSecretId()).orElse(null);
        if (secret == null || !secret.isWritable())
            return;

        try {
            if (result.getNullValue())
                throw new IllegalArgumentException("Writable secret value must not be null");

            EncryptedSecretValue encrypted = encryptionService.encrypt(result.getValue());
            secret.replaceEncryptedValue(
                    encrypted.encryptedValue(), encrypted.encryptionIv(), encrypted.valueHash(),
                    encrypted.hashSalt(), encrypted.encryptionVersion()
            );
            secretRepository.flush();
            eventLogger.successAfterCommit(
                    "SECRET_OVERWRITTEN_BY_ALERT",
                    context(alertId, alertName, executionId, result, secret)
            );
        } catch (RuntimeException exception) {
            Map<String, Object> data = context(alertId, alertName, executionId, result, secret);
            data.put("reason", exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage());
            eventLogger.errorAfterCommit("SECRET_OVERWRITE_REJECTED", data);
        }
    }

    private static Map<String, Object> context(long alertId, String alertName, UUID executionId, WritableSecretValue result, ApplicationSecret secret) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("secretId", secret.getId());
        data.put("secretName", secret.getName());
        data.put("alertId", alertId);
        data.put("alertName", alertName);
        data.put("executionId", executionId);
        data.put("parameterName", result.getParameterName());
        data.put("valueRevision", secret.getValueRevision());
        return data;
    }
}
