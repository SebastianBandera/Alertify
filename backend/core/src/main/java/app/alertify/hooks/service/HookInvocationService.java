package app.alertify.hooks.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.hooks.HookInvocationRejectedException;
import app.alertify.hooks.api.HookAcceptedResponse;
import app.alertify.hooks.api.HookInvocationResponse;
import app.alertify.hooks.model.Hook;
import app.alertify.hooks.model.HookTargetType;
import app.alertify.jpa.repository.HookRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.services.secret.SecretEncryptionService;
import app.alertify.services.secret.SecretNotRecoverableException;

@Service
public class HookInvocationService {

    private final HookRepository hookRepository;
    private final SecretEncryptionService encryptionService;
    private final HookAdmissionService admission;
    private final HookInvocationPersistenceService persistence;
    private final HookCoordinator coordinator;
    private final ApplicationEventLogger eventLogger;

    public HookInvocationService(HookRepository hookRepository, SecretEncryptionService encryptionService, HookAdmissionService admission, HookInvocationPersistenceService persistence, HookCoordinator coordinator, ApplicationEventLogger eventLogger) {
        this.hookRepository = hookRepository;
        this.encryptionService = encryptionService;
        this.admission = admission;
        this.persistence = persistence;
        this.coordinator = coordinator;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public HookAcceptedResponse invoke(UUID publicId, String token) {
        Hook hook = enabled(publicId);
        authenticate(hook, token);
        boolean hasEnabledTarget = hook.getTargets().stream().anyMatch(target -> target.getTargetType() == HookTargetType.ALERT
                ? target.getAlert().isEnabled() : target.getProcedure().isEnabled());
        if (!hasEnabledTarget) {
            reject(hook, "HOOK_NO_ENABLED_TARGETS");
            throw new HookInvocationRejectedException(HttpStatus.CONFLICT, "HOOK_NO_ENABLED_TARGETS", "The hook has no enabled targets");
        }

        UUID invocationId = UUID.randomUUID();
        try {
            admission.admit(hook, invocationId);
            persistence.accept(hook, invocationId);
        } catch (HookInvocationRejectedException exception) {
            reject(hook, exception.getCode());
            throw exception;
        } catch (RuntimeException exception) {
            if (admission.limited(hook))
                admission.rollback(hook.getId(), invocationId);

            throw exception;
        }

        Map<String, Object> data = data(hook);
        data.put("invocationId", invocationId);
        eventLogger.success("HOOK_INVOCATION_ACCEPTED", data);
        coordinator.submit(invocationId, hook.getId(), admission.limited(hook));
        return new HookAcceptedResponse(invocationId);
    }

    @Transactional(readOnly = true)
    public HookInvocationResponse status(UUID publicId, UUID invocationId, String token) {
        Hook hook = enabled(publicId);
        authenticate(hook, token);
        return persistence.publicStatus(publicId, invocationId);
    }

    private Hook enabled(UUID publicId) {
        return hookRepository.findByPublicIdAndEnabledTrue(publicId).orElseThrow(() -> {
            eventLogger.failure("HOOK_INVOCATION_REJECTED", Map.of("publicId", publicId, "reason", "NOT_FOUND_OR_DISABLED"));
            return new HookInvocationRejectedException(HttpStatus.NOT_FOUND, "HOOK_NOT_FOUND", "Hook was not found");
        });
    }

    private void authenticate(Hook hook, String supplied) {
        if (hook.getTokenSecret() == null)
            return;

        if (supplied == null) {
            reject(hook, "INVALID_TOKEN");
            throw unauthorized();
        }

        String expected;
        try {
            expected = encryptionService.decrypt(hook.getTokenSecret());
        } catch (SecretNotRecoverableException exception) {
            reject(hook, "TOKEN_UNRECOVERABLE");
            throw new HookInvocationRejectedException(HttpStatus.SERVICE_UNAVAILABLE, "HOOK_TOKEN_UNAVAILABLE", "Hook token validation is temporarily unavailable");
        }

        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] suppliedBytes = supplied.getBytes(StandardCharsets.UTF_8);
        byte[] expectedHash = sha256(expectedBytes);
        byte[] suppliedHash = sha256(suppliedBytes);
        try {
            if (!MessageDigest.isEqual(expectedHash, suppliedHash)) {
                reject(hook, "INVALID_TOKEN");
                throw unauthorized();
            }
        } finally {
            Arrays.fill(expectedBytes, (byte) 0);
            Arrays.fill(suppliedBytes, (byte) 0);
            Arrays.fill(expectedHash, (byte) 0);
            Arrays.fill(suppliedHash, (byte) 0);
        }
    }

    private void reject(Hook hook, String reason) {
        Map<String, Object> data = data(hook);
        data.put("reason", reason);
        eventLogger.failure("HOOK_INVOCATION_REJECTED", data);
    }

    private static Map<String, Object> data(Hook hook) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hookId", hook.getId());
        data.put("publicId", hook.getPublicId());
        data.put("hookName", hook.getName());
        return data;
    }

    private static HookInvocationRejectedException unauthorized() {
        return new HookInvocationRejectedException(HttpStatus.UNAUTHORIZED, "HOOK_TOKEN_INVALID", "Hook token is missing or invalid");
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
