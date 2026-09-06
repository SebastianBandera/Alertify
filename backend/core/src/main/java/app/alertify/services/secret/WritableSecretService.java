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
            applyOne(new Owner("alert", alertId, alertName, "SECRET_OVERWRITTEN_BY_ALERT"), executionId, value);
    }

    public void applyProcedure(long procedureId, String procedureName, UUID executionId, Iterable<WritableSecretValue> values) {
        for (WritableSecretValue value : values)
            applyOne(new Owner("procedure", procedureId, procedureName,
                    "SECRET_OVERWRITTEN_BY_PROCEDURE"), executionId, value);
    }

    private void applyOne(Owner owner, UUID executionId, WritableSecretValue result) {
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
            eventLogger.successAfterCommit(owner.successEvent(), context(owner, executionId, result, secret));
        } catch (RuntimeException exception) {
            Map<String, Object> data = context(owner, executionId, result, secret);
            data.put("reason", exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage());
            eventLogger.errorAfterCommit("SECRET_OVERWRITE_REJECTED", data);
        }
    }

    private static Map<String, Object> context(Owner owner, UUID executionId, WritableSecretValue result, ApplicationSecret secret) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("secretId", secret.getId());
        data.put("secretName", secret.getName());
        data.put(owner.type() + "Id", owner.id());
        data.put(owner.type() + "Name", owner.name());
        data.put("executionId", executionId);
        data.put("parameterName", result.getParameterName());
        data.put("valueRevision", secret.getValueRevision());
        return data;
    }

    private record Owner(String type, long id, String name, String successEvent) { }
}
