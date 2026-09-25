package app.alertify.systemconfiguration.service;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidConfigurationValueException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.dashboard.DashboardHistoryWindow;
import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.jpa.repository.SystemConfigurationRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.systemconfiguration.api.SystemConfigurationRegenerateRequest;
import app.alertify.systemconfiguration.api.SystemConfigurationResponse;
import app.alertify.systemconfiguration.api.SystemConfigurationUpdateRequest;
import app.alertify.system.SystemConfigurationChangedEvent;
import app.alertify.services.secret.SymmetricKeyService;

/**
 * Administrative lifecycle for system configurations: entries are seeded by
 * migration, never created or deleted through the API. Only their value can
 * be changed.
 */
@Service
public class SystemConfigurationService {

    private static final int RANDOM_VALUE_BYTES = 32;
    private static final String SYSTEM_CONFIGURATION_ID = "systemConfigurationId";
    private static final String KEY_PART_NAME = "KEY_PART";
    private static final String KEY_TRANSITION_DELIMITER = "->";

    /**
     * Names allowed to use {@link #regenerate}. An opaque random value only
     * makes sense for a secret-shaped value nobody needs to inspect;
     * regenerating a visible, structured entry (e.g. {@code CRON_QUIET_HOURS})
     * would silently overwrite it with meaningless random hex instead of the
     * shape its readers expect. Deliberately an explicit allowlist rather
     * than inferred from {@code valueHidden} alone: a future hidden entry is
     * not guaranteed to be a flat opaque string either.
     */
    private static final Set<String> REGENERATABLE_NAMES = Set.of("KEY_PART");

    private final SystemConfigurationRepository repository;
    private final ApplicationEventLogger eventLogger;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SymmetricKeyService symmetricKeyService;
    private final SecureRandom secureRandom = new SecureRandom();

    public SystemConfigurationService(SystemConfigurationRepository repository, ApplicationEventLogger eventLogger, ApplicationEventPublisher applicationEventPublisher, SymmetricKeyService symmetricKeyService) {
        this.repository = repository;
        this.eventLogger = eventLogger;
        this.applicationEventPublisher = applicationEventPublisher;
        this.symmetricKeyService = symmetricKeyService;
    }

    @Transactional(readOnly = true)
    public Page<SystemConfigurationResponse> search(Pageable pageable) {
        Page<SystemConfigurationResponse> result = repository.findAll(pageable).map(SystemConfigurationMapper::toResponse);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.getNumber());
        data.put("size", result.getSize());
        data.put("totalElements", result.getTotalElements());
        eventLogger.successAfterCommit("SYSTEM_CONFIGURATION_PAGE_VIEWED", data);
        return result;
    }

    @Transactional(readOnly = true)
    public SystemConfigurationResponse get(Long id) {
        SystemConfiguration configuration = find(id);
        eventLogger.success(
                "SYSTEM_CONFIGURATION_VIEWED",
                Map.of(SYSTEM_CONFIGURATION_ID, configuration.getId(), "name", configuration.getName(), "version", configuration.getVersion())
        );
        return SystemConfigurationMapper.toResponse(configuration);
    }

    @Transactional
    public SystemConfigurationResponse update(Long id, SystemConfigurationUpdateRequest request) {
        SystemConfiguration configuration = find(id);
        verifyVersion(configuration.getVersion(), request.version());
        validateValue(configuration.getName(), request.value());

        JsonNode persistedValue = KEY_PART_NAME.equals(configuration.getName())
                ? prepareKeyPartTransition(configuration, request.value())
                : request.value();

        Set<String> changedFields = new LinkedHashSet<>();

        if (!configuration.getValue().equals(persistedValue)) {
            configuration.changeValue(persistedValue);
            changedFields.add("value");
        }

        if (!changedFields.isEmpty())
            repository.flush();

        Map<String, Object> logData = new LinkedHashMap<>();
        logData.put(SYSTEM_CONFIGURATION_ID, id);
        logData.put("name", configuration.getName());
        logData.put("changed", !changedFields.isEmpty());
        logData.put("changedFields", changedFields);
        eventLogger.successAfterCommit("SYSTEM_CONFIGURATION_UPDATED", logData);
        boolean changed = !changedFields.isEmpty();
        applicationEventPublisher.publishEvent(new SystemConfigurationChangedEvent(
                "MAINTENANCE_MODE".equals(configuration.getName()) && changed,
                "CRON_QUIET_HOURS".equals(configuration.getName()) && changed
        ));
        return SystemConfigurationMapper.toResponse(configuration);
    }

    @Transactional
    public SystemConfigurationResponse regenerate(Long id, SystemConfigurationRegenerateRequest request) {
        SystemConfiguration configuration = find(id);
        verifyVersion(configuration.getVersion(), request.version());
        if (!REGENERATABLE_NAMES.contains(configuration.getName())) {
            throw new ConflictException(
                    "System configuration '" + configuration.getName() + "' does not support regeneration"
            );
        }

        byte[] randomBytes = new byte[RANDOM_VALUE_BYTES];
        secureRandom.nextBytes(randomBytes);
        String newValue;
        try {
            newValue = HexFormat.of().formatHex(randomBytes);
        } finally {
            Arrays.fill(randomBytes, (byte) 0);
        }
        configuration.changeValue(prepareKeyPartTransition(configuration, StringNode.valueOf(newValue)));
        repository.flush();

        eventLogger.successAfterCommit(
                "SYSTEM_CONFIGURATION_UPDATED",
                Map.of(SYSTEM_CONFIGURATION_ID, id, "name", configuration.getName(), "changed", true, "changedFields", Set.of("value"))
        );
        return SystemConfigurationMapper.toResponse(configuration);
    }

    @Transactional
    public SystemConfigurationResponse finalizeKeyRotation(Long id, SystemConfigurationRegenerateRequest request) {
        SystemConfiguration configuration = find(id);
        verifyVersion(configuration.getVersion(), request.version());
        if (!KEY_PART_NAME.equals(configuration.getName()))
            throw new ConflictException("System configuration '" + configuration.getName() + "' does not support key-rotation finalization");

        String currentValue = keyPartValue(configuration);
        int delimiterIndex = currentValue.indexOf(KEY_TRANSITION_DELIMITER);
        if (delimiterIndex < 0)
            throw new ConflictException("KEY_PART does not have a pending transition");

        if (!symmetricKeyService.activeKeyMatchesCurrentTarget())
            throw new ConflictException("KEY_PART cannot be finalized until the backend has restarted and completed key rotation");

        configuration.changeValue(StringNode.valueOf(currentValue.substring(delimiterIndex + KEY_TRANSITION_DELIMITER.length())));
        repository.flush();
        eventLogger.successAfterCommit("SYSTEM_CONFIGURATION_UPDATED", Map.of(SYSTEM_CONFIGURATION_ID, id, "name", configuration.getName(), "changed", true, "changedFields", Set.of("value")));
        return SystemConfigurationMapper.toResponse(configuration);
    }

    /* Entries whose readers expect a specific shape reject anything else up front. */
    private static void validateValue(String name, JsonNode value) {
        if (KEY_PART_NAME.equals(name)) {
            if (!value.isString() || value.stringValue() == null || value.stringValue().isEmpty())
                throw new InvalidConfigurationValueException("KEY_PART must be a non-empty string");

            if (value.stringValue().contains(KEY_TRANSITION_DELIMITER))
                throw new InvalidConfigurationValueException("KEY_PART cannot contain the reserved transition delimiter");
        }

        if (DashboardHistoryWindow.CONFIGURATION_NAME.equals(name) && !DashboardHistoryWindow.isValid(value)) {
            throw new InvalidConfigurationValueException(
                    name + " must be {\"days\": N} with N a whole number from "
                            + DashboardHistoryWindow.MIN_DAYS + " to " + DashboardHistoryWindow.MAX_DAYS
            );
        }
    }

    private static JsonNode prepareKeyPartTransition(SystemConfiguration configuration, JsonNode requestedValue) {
        String currentValue = keyPartValue(configuration);
        if (currentValue.contains(KEY_TRANSITION_DELIMITER))
            throw new ConflictException("KEY_PART already has a pending transition");

        String newValue = requestedValue.stringValue();
        if (currentValue.equals(newValue))
            return configuration.getValue();

        return StringNode.valueOf(currentValue + KEY_TRANSITION_DELIMITER + newValue);
    }

    private static String keyPartValue(SystemConfiguration configuration) {
        if (!configuration.getValue().isString() || configuration.getValue().stringValue() == null || configuration.getValue().stringValue().isEmpty())
            throw new IllegalStateException("KEY_PART must contain a non-empty string");

        return configuration.getValue().stringValue();
    }

    private SystemConfiguration find(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("System configuration " + id + " was not found"));
    }

    private static void verifyVersion(long currentVersion, long requestedVersion) {
        if (currentVersion != requestedVersion) {
            throw new ConflictException(
                    "System configuration was modified by another request; reload it and try again"
            );
        }
    }
}
