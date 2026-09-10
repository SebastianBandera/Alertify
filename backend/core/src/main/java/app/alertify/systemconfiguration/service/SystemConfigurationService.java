package app.alertify.systemconfiguration.service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.node.StringNode;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.jpa.repository.SystemConfigurationRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.systemconfiguration.api.SystemConfigurationRegenerateRequest;
import app.alertify.systemconfiguration.api.SystemConfigurationResponse;
import app.alertify.systemconfiguration.api.SystemConfigurationUpdateRequest;

/**
 * Administrative lifecycle for system configurations: entries are seeded by
 * migration, never created or deleted through the API. Only their value can
 * be changed.
 */
@Service
public class SystemConfigurationService {

    private static final int RANDOM_VALUE_BYTES = 32;

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
    private final SecureRandom secureRandom = new SecureRandom();

    public SystemConfigurationService(SystemConfigurationRepository repository, ApplicationEventLogger eventLogger) {
        this.repository = repository;
        this.eventLogger = eventLogger;
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
                Map.of("systemConfigurationId", configuration.getId(), "name", configuration.getName(), "version", configuration.getVersion())
        );
        return SystemConfigurationMapper.toResponse(configuration);
    }

    @Transactional
    public SystemConfigurationResponse update(Long id, SystemConfigurationUpdateRequest request) {
        SystemConfiguration configuration = find(id);
        verifyVersion(configuration.getVersion(), request.version());

        Set<String> changedFields = new LinkedHashSet<>();

        if (!configuration.getValue().equals(request.value())) {
            configuration.changeValue(request.value());
            changedFields.add("value");
        }

        if (!changedFields.isEmpty())
            repository.flush();

        Map<String, Object> logData = new LinkedHashMap<>();
        logData.put("systemConfigurationId", id);
        logData.put("name", configuration.getName());
        logData.put("changed", !changedFields.isEmpty());
        logData.put("changedFields", changedFields);
        eventLogger.successAfterCommit("SYSTEM_CONFIGURATION_UPDATED", logData);
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
        configuration.changeValue(StringNode.valueOf(HexFormat.of().formatHex(randomBytes)));
        repository.flush();

        eventLogger.successAfterCommit(
                "SYSTEM_CONFIGURATION_UPDATED",
                Map.of("systemConfigurationId", id, "name", configuration.getName(), "changed", true, "changedFields", Set.of("value"))
        );
        return SystemConfigurationMapper.toResponse(configuration);
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
