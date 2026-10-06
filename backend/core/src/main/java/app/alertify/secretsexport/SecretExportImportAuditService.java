package app.alertify.secretsexport;

import java.time.Instant;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.jpa.entity.ApplicationLog;
import app.alertify.jpa.entity.ApplicationLogEvent;
import app.alertify.jpa.entity.ApplicationLogLevelDefinition;
import app.alertify.jpa.entity.ApplicationLogSource;
import app.alertify.jpa.repository.ApplicationLogEventRepository;
import app.alertify.jpa.repository.ApplicationLogLevelDefinitionRepository;
import app.alertify.jpa.repository.ApplicationLogRepository;
import app.alertify.jpa.repository.ApplicationLogSourceRepository;
import app.alertify.logging.ApplicationLogLevel;
import app.alertify.logging.ApplicationLogOutcome;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mandatory audit writer for the internal secrets tool. Unlike the regular
 * application event logger, audit persistence failures are propagated so an
 * export cannot be delivered and an import cannot commit without its event.
 */
@Service
@Profile(SecretExportImportConfiguration.PROFILE)
class SecretExportImportAuditService {

    private static final String SOURCE = "alertify-backend";
    private static final String SYSTEM_ACTOR = "system";
    private static final String ORIGIN = "secrets-tool";
    private static final String EXPORT_EVENT = "SECRET_EXPORT";
    private static final String IMPORT_EVENT = "SECRET_IMPORT";

    private final ApplicationLogRepository logRepository;
    private final ApplicationLogLevelDefinitionRepository levelRepository;
    private final ApplicationLogSourceRepository sourceRepository;
    private final ApplicationLogEventRepository eventRepository;
    private final JsonMapper jsonMapper;

    SecretExportImportAuditService(ApplicationLogRepository logRepository, ApplicationLogLevelDefinitionRepository levelRepository, ApplicationLogSourceRepository sourceRepository, ApplicationLogEventRepository eventRepository, JsonMapper jsonMapper) {
        this.logRepository = logRepository;
        this.levelRepository = levelRepository;
        this.sourceRepository = sourceRepository;
        this.eventRepository = eventRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordExportSuccess(int secretCount, int systemConfigurationCount) {
        persist(ApplicationLogLevel.INFO, EXPORT_EVENT, ApplicationLogOutcome.SUCCESS, Map.of("origin", ORIGIN, "secretCount", secretCount, "systemConfigurationCount", systemConfigurationCount));
    }

    @Transactional
    public void recordImportSuccess(int secretsCreated, int secretsSkipped, int systemConfigurationsCreated, int systemConfigurationsSkipped) {
        persist(ApplicationLogLevel.INFO, IMPORT_EVENT, ApplicationLogOutcome.SUCCESS, Map.of("origin", ORIGIN, "secretsCreated", secretsCreated, "secretsSkipped", secretsSkipped, "systemConfigurationsCreated", systemConfigurationsCreated, "systemConfigurationsSkipped", systemConfigurationsSkipped));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(boolean export) {
        persist(ApplicationLogLevel.WARN, export ? EXPORT_EVENT : IMPORT_EVENT, ApplicationLogOutcome.FAILURE, Map.of("origin", ORIGIN, "failureCategory", "OPERATION_FAILED"));
    }

    private void persist(ApplicationLogLevel level, String event, ApplicationLogOutcome outcome, Map<String, ?> data) {
        ApplicationLogLevelDefinition levelDefinition = levelRepository.findByCode(level.name()).orElseThrow(() -> missing("level", level.name()));
        ApplicationLogSource source = sourceRepository.findByCode(SOURCE).orElseThrow(() -> missing("source", SOURCE));
        ApplicationLogEvent eventDefinition = eventRepository.findByCode(event).orElseThrow(() -> missing("event", event));
        logRepository.saveAndFlush(new ApplicationLog(Instant.now(), levelDefinition, source, eventDefinition, outcome,
                SYSTEM_ACTOR, SYSTEM_ACTOR, false, null, null, null, jsonMapper.valueToTree(data)));
    }

    private static IllegalStateException missing(String catalog, String code) {
        return new IllegalStateException("Unknown application log " + catalog + " code: " + code);
    }
}
