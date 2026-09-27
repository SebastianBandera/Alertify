package app.alertify.secretsexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

class SecretExportImportAuditServiceTest {

    @Test
    void recordsAnExportWithoutSensitiveMetadata() {
        AuditFixture fixture = new AuditFixture("SECRET_EXPORT", ApplicationLogLevel.INFO);

        fixture.service.recordExportSuccess(3, 2);

        ApplicationLog log = fixture.savedLog();
        assertThat(log.getEvent()).isEqualTo("SECRET_EXPORT");
        assertThat(log.getOutcome()).isEqualTo(ApplicationLogOutcome.SUCCESS);
        assertThat(log.getUserSubject()).isEqualTo("system");
        assertThat(log.getUsername()).isEqualTo("system");
        assertThat(log.getRequestId()).isNull();
        assertThat(log.getPath()).isNull();
        assertThat(log.getData().get("origin").asText()).isEqualTo("secrets-tool");
        assertThat(log.getData().get("secretCount").asInt()).isEqualTo(3);
        assertThat(log.getData().get("systemConfigurationCount").asInt()).isEqualTo(2);
        assertThat(log.getData().size()).isEqualTo(3);
    }

    @Test
    void recordsAnImportFailureWithOnlyItsFixedCategory() {
        AuditFixture fixture = new AuditFixture("SECRET_IMPORT", ApplicationLogLevel.WARN);

        fixture.service.recordFailure(false);

        ApplicationLog log = fixture.savedLog();
        assertThat(log.getEvent()).isEqualTo("SECRET_IMPORT");
        assertThat(log.getOutcome()).isEqualTo(ApplicationLogOutcome.FAILURE);
        assertThat(log.getData().get("origin").asText()).isEqualTo("secrets-tool");
        assertThat(log.getData().get("failureCategory").asText()).isEqualTo("OPERATION_FAILED");
        assertThat(log.getData().size()).isEqualTo(2);
    }

    @Test
    void recordsImportCountsWithoutImportedNames() {
        AuditFixture fixture = new AuditFixture("SECRET_IMPORT", ApplicationLogLevel.INFO);

        fixture.service.recordImportSuccess(4, 3, 2, 1);

        ApplicationLog log = fixture.savedLog();
        assertThat(log.getOutcome()).isEqualTo(ApplicationLogOutcome.SUCCESS);
        assertThat(log.getData().get("secretsCreated").asInt()).isEqualTo(4);
        assertThat(log.getData().get("secretsSkipped").asInt()).isEqualTo(3);
        assertThat(log.getData().get("systemConfigurationsCreated").asInt()).isEqualTo(2);
        assertThat(log.getData().get("systemConfigurationsSkipped").asInt()).isEqualTo(1);
        assertThat(log.getData().size()).isEqualTo(5);
    }

    private static class AuditFixture {

        private final ApplicationLogRepository logRepository = mock(ApplicationLogRepository.class);
        private final SecretExportImportAuditService service;

        AuditFixture(String eventCode, ApplicationLogLevel level) {
            ApplicationLogLevelDefinitionRepository levelRepository = mock(ApplicationLogLevelDefinitionRepository.class);
            ApplicationLogSourceRepository sourceRepository = mock(ApplicationLogSourceRepository.class);
            ApplicationLogEventRepository eventRepository = mock(ApplicationLogEventRepository.class);
            ApplicationLogLevelDefinition levelDefinition = mock(ApplicationLogLevelDefinition.class);
            ApplicationLogSource source = mock(ApplicationLogSource.class);
            ApplicationLogEvent event = mock(ApplicationLogEvent.class);

            when(levelDefinition.getCode()).thenReturn(level.name());
            when(source.getCode()).thenReturn("alertify-backend");
            when(event.getCode()).thenReturn(eventCode);
            when(levelRepository.findByCode(level.name())).thenReturn(Optional.of(levelDefinition));
            when(sourceRepository.findByCode("alertify-backend")).thenReturn(Optional.of(source));
            when(eventRepository.findByCode(eventCode)).thenReturn(Optional.of(event));
            service = new SecretExportImportAuditService(logRepository, levelRepository, sourceRepository,
                    eventRepository, JsonMapper.builder().build());
        }

        ApplicationLog savedLog() {
            ArgumentCaptor<ApplicationLog> captor = ArgumentCaptor.forClass(ApplicationLog.class);
            verify(logRepository).saveAndFlush(captor.capture());
            return captor.getValue();
        }
    }
}
