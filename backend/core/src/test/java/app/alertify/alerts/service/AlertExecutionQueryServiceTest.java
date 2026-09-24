package app.alertify.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertExecution;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.WorkerCapability;

@ExtendWith(MockitoExtension.class)
class AlertExecutionQueryServiceTest {

    @Mock private AlertExecutionRepository executionRepository;
    @Mock private ApplicationEventLogger eventLogger;

    @Test
    void combinesFiltersAndIncludesTemplateMetadata() {
        long alertId = 11L;
        long templateId = 7L;
        UUID executionId = UUID.randomUUID();
        AlertTemplateDefinition template = new AlertTemplateDefinition("example.Template", "template.name", "template.description", "Template.java", WorkerCapability.STANDARD);
        ReflectionTestUtils.setField(template, "id", templateId);
        Alert alert = new Alert(template, "Daily check", null, "-", true, Set.of());
        ReflectionTestUtils.setField(alert, "id", alertId);
        Instant startedAt = Instant.parse("2026-09-24T12:00:00Z");
        AlertExecution execution = AlertExecution.result(executionId, alert, null, AlertExecutionStatus.WARN, startedAt, startedAt.plusSeconds(1), startedAt.plusSeconds(2), null);
        ReflectionTestUtils.setField(execution, "id", 31L);
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "startedAt"));
        when(executionRepository.search(alertId, templateId, AlertExecutionStatus.WARN, executionId, pageable)).thenReturn(new PageImpl<>(List.of(execution), pageable, 1));

        var result = new AlertExecutionQueryService(executionRepository, eventLogger).search(alertId, templateId, AlertExecutionStatus.WARN, executionId, pageable);

        verify(executionRepository).search(alertId, templateId, AlertExecutionStatus.WARN, executionId, pageable);
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().getFirst().templateId()).isEqualTo(templateId);
        assertThat(result.getContent().getFirst().templateNameKey()).isEqualTo("template.name");
    }
}
