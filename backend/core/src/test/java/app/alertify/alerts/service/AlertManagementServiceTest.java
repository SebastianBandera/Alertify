package app.alertify.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.alerts.api.AlertDeletionImpactResponse;
import app.alertify.alerts.execution.AlertExecutionOrchestrator;
import app.alertify.alerts.execution.AlertScheduleService;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.WorkerCapability;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertManagementServiceTest {

    @Mock private AlertRepository alertRepository;
    @Mock private AlertTemplateDefinitionRepository templateRepository;
    @Mock private AlertTemplateParameterDefinitionRepository templateParameterRepository;
    @Mock private AlertParameterValueRepository parameterValueRepository;
    @Mock private AlertExecutionRepository executionRepository;
    @Mock private AlertStateRepository stateRepository;
    @Mock private ApplicationConfigurationRepository configurationRepository;
    @Mock private ApplicationSecretRepository secretRepository;
    @Mock private TagRepository tagRepository;
    @Mock private ApplicationEventLogger eventLogger;
    @Mock private AlertScheduleService scheduleService;
    @Mock private AlertExecutionOrchestrator executionOrchestrator;

    @Test
    void deletesTheExecutionHistoryBeforeTheAlertItself() {
        Alert alert = alert("chequeo-cert");
        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert));
        when(executionRepository.deleteByAlertId(5L)).thenReturn(1235);
        when(parameterValueRepository.findAllByAlertIdOrdered(5L)).thenReturn(List.of());

        service().delete(5L, 0L);

        // The alert_executions foreign key is ON DELETE RESTRICT, so order matters.
        InOrder order = inOrder(executionRepository, parameterValueRepository, alertRepository);
        order.verify(executionRepository).deleteByAlertId(5L);
        order.verify(parameterValueRepository).deleteAll(List.of());
        order.verify(alertRepository).delete(alert);
        verify(scheduleService).removeAfterCommit(5L);
    }

    @Test
    void recordsHowMuchHistoryTheDeletionRemoved() {
        Alert alert = alert("chequeo-cert");
        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert));
        when(executionRepository.deleteByAlertId(5L)).thenReturn(1235);
        when(parameterValueRepository.findAllByAlertIdOrdered(5L)).thenReturn(List.of());

        service().delete(5L, 0L);

        ArgumentCaptor<Map<String, Object>> deleted = ArgumentCaptor.captor();
        verify(eventLogger).successAfterCommit(org.mockito.ArgumentMatchers.eq("ALERT_DELETED"), deleted.capture());
        assertThat(deleted.getValue())
                .containsEntry("alertId", 5L)
                .containsEntry("name", "chequeo-cert")
                .containsEntry("executionsDeleted", 1235);
    }

    @Test
    void refusesToDeleteAnAlertThatIsCurrentlyRunning() {
        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert("chequeo-cert")));
        when(executionOrchestrator.isRunning(5L)).thenReturn(true);

        assertThatThrownBy(() -> service().delete(5L, 0L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("currently running");

        verify(executionRepository, never()).deleteByAlertId(anyLong());
        verify(alertRepository, never()).delete(any(Alert.class));
    }

    @Test
    void rejectsDeletingAnUnknownAlert() {
        when(alertRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(5L, 0L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsDeletingWithAStaleVersion() {
        Alert alert = alert("chequeo-cert");
        ReflectionTestUtils.setField(alert, "version", 3L);
        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> service().delete(5L, 0L))
                .isInstanceOf(ConflictException.class);

        verify(executionRepository, never()).deleteByAlertId(anyLong());
    }

    @Test
    void reportsHowManyExecutionsADeletionWouldRemove() {
        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert("chequeo-cert")));
        when(executionRepository.countByAlert_Id(5L)).thenReturn(1235L);

        AlertDeletionImpactResponse impact = service().deletionImpact(5L);

        assertThat(impact.name()).isEqualTo("chequeo-cert");
        assertThat(impact.executionCount()).isEqualTo(1235L);
    }

    @Test
    void rejectsTheDeletionImpactOfAnUnknownAlert() {
        when(alertRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().deletionImpact(5L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private AlertManagementService service() {
        return new AlertManagementService(
                alertRepository, templateRepository, templateParameterRepository, parameterValueRepository,
                executionRepository, stateRepository, configurationRepository, secretRepository,
                tagRepository, eventLogger, scheduleService, executionOrchestrator
        );
    }

    private static Alert alert(String name) {
        AlertTemplateDefinition template = new AlertTemplateDefinition(
                "app.alertify.alerts.templates.HttpsCertificateExpiryAlertTemplate",
                "name.key", "description.key", "source/path.java", WorkerCapability.STANDARD
        );
        ReflectionTestUtils.setField(template, "id", 7L);
        Alert alert = new Alert(template, name, null, "0 0 8 * * *", true, false, Set.of());
        ReflectionTestUtils.setField(alert, "id", 5L);
        return alert;
    }
}
