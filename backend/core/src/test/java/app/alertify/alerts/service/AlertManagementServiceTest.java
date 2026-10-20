package app.alertify.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.scheduling.support.CronExpression;

import app.alertify.alerts.api.AlertDeletionImpactResponse;
import app.alertify.alerts.api.AlertCreateRequest;
import app.alertify.alerts.api.AlertParameterValueRequest;
import app.alertify.alerts.api.AlertResponse;
import app.alertify.alerts.api.AlertUpdateRequest;
import app.alertify.alerts.execution.AlertExecutionOrchestrator;
import app.alertify.alerts.execution.AlertExecutionTrigger;
import app.alertify.alerts.execution.AlertScheduleService;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertParameterValue;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.model.SmartExecutionPolicy;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.ai.api.AiAlertUpdateRequest;
import app.alertify.ai.api.AiAlertParameterValue;
import app.alertify.ai.api.AiAlertParameterSource;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.dashboard.DashboardEventPublisher;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.WorkerCapability;
import jakarta.persistence.EntityManager;

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
    @Mock private ProcedureRepository procedureRepository;
    @Mock private ApplicationEventLogger eventLogger;
    @Mock private AlertScheduleService scheduleService;
    @Mock private AlertExecutionOrchestrator executionOrchestrator;
    @Mock private DashboardEventPublisher dashboardEventPublisher;
    @Mock private EntityManager entityManager;

    @Test
    void acceptsAnEmptyTextValueForARequiredStringParameter() {
        AlertTemplateDefinition template = alert("template-holder").getTemplate();
        AlertTemplateParameterDefinition steps = requiredSteps(template);
        when(templateRepository.findById(7L)).thenReturn(Optional.of(template));
        when(templateParameterRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(7L)).thenReturn(List.of(steps));
        when(alertRepository.saveAndFlush(any(Alert.class))).thenAnswer(invocation -> {
            Alert saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 5L);
            return saved;
        });
        when(parameterValueRepository.save(any(AlertParameterValue.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AlertResponse response = service().create(new AlertCreateRequest(
                7L, "playwright", null, "-", true,
                List.of(new AlertParameterValueRequest("steps", AlertParameterSource.TEXT, "", null, null)), Set.of()
        ));

        assertThat(response.parameters()).singleElement().satisfies(value -> {
            assertThat(value.parameterKey()).isEqualTo("steps");
            assertThat(value.source()).isEqualTo(AlertParameterSource.TEXT);
            assertThat(value.textValue()).isEmpty();
        });
    }

    @Test
    void rejectsAnOmittedRequiredStringParameter() {
        AlertTemplateDefinition template = alert("template-holder").getTemplate();
        AlertTemplateParameterDefinition steps = requiredSteps(template);
        when(templateRepository.findById(7L)).thenReturn(Optional.of(template));
        when(templateParameterRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(7L)).thenReturn(List.of(steps));
        when(alertRepository.saveAndFlush(any(Alert.class))).thenAnswer(invocation -> {
            Alert saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 5L);
            return saved;
        });

        assertThatThrownBy(() -> service().create(new AlertCreateRequest(
                7L, "playwright", null, "-", true, List.of(), Set.of()
        ))).hasMessageContaining("Required parameter 'steps' has no value");
    }

    @Test
    void rejectsDuplicateParameterKeysBeforeSavingAnyParameter() {
        var snapshot = alert("duplicate-parameters");
        when(alertRepository.findById(5L)).thenReturn(Optional.of(snapshot));
        when(templateParameterRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(7L))
                .thenReturn(List.of(requiredSteps(snapshot.getTemplate())));
        when(parameterValueRepository.findAllByAlertIdOrdered(5L)).thenReturn(List.of());
        var request = new AlertUpdateRequest(snapshot.getVersion(), snapshot.getName(), snapshot.getDescription(),
                snapshot.getCronExpression(), snapshot.isEnabled(), snapshot.isConcurrentExecutionAllowed(), List.of(
                        new AlertParameterValueRequest("steps", AlertParameterSource.TEXT, "FIRST", null, null),
                        new AlertParameterValueRequest("steps", AlertParameterSource.TEXT, "DUPLICATE", null, null)), Set.of());

        assertThatThrownBy(() -> service().update(5L, request)).hasMessageContaining("provided more than once");
        verify(parameterValueRepository, never()).save(any());
        verify(alertRepository, never()).flush();
    }

    @Test
    void defaultsNewSmartAlertsToOncePerInterval() {
        AlertTemplateDefinition template = alert("template-holder").getTemplate();
        when(templateRepository.findById(7L)).thenReturn(Optional.of(template));
        when(alertRepository.saveAndFlush(any(Alert.class))).thenAnswer(invocation -> {
            Alert saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 5L);
            return saved;
        });

        AlertResponse response = service().create(new AlertCreateRequest(
                7L, "smart", null, "-", true, false, List.of(), Set.of(), null,
                true, 23, null
        ));

        assertThat(response.smartExecutionPolicy()).isEqualTo(SmartExecutionPolicy.ONCE_PER_INTERVAL);
        assertThat(response.smartExecutionIntervalHours()).isEqualTo(23);
    }

    @Test
    void usesTheTemplatePersistentIssuesDefaultWhenCreationOmitsTheOption() {
        AlertTemplateDefinition template = template(true);
        when(templateRepository.findById(7L)).thenReturn(Optional.of(template));
        when(alertRepository.saveAndFlush(any(Alert.class))).thenAnswer(invocation -> {
            Alert saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 5L);
            return saved;
        });

        AlertResponse response = service().create(new AlertCreateRequest(
                7L, "reminder", null, "-", true, false, List.of(), Set.of(), null,
                false, null, null
        ));

        assertThat(response.persistentIssuesSince()).isNotNull();
    }

    @Test
    void explicitPersistentIssuesChoiceOverridesTheTemplateDefault() {
        AlertTemplateDefinition template = template(true);
        when(templateRepository.findById(7L)).thenReturn(Optional.of(template));
        when(alertRepository.saveAndFlush(any(Alert.class))).thenAnswer(invocation -> {
            Alert saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 5L);
            return saved;
        });

        AlertResponse response = service().create(new AlertCreateRequest(
                7L, "reminder", null, "-", true, false, List.of(), Set.of(), false,
                false, null, null
        ));

        assertThat(response.persistentIssuesSince()).isNull();
    }

    @Test
    void rejectsInvalidSpringCronTemplateParameters() {
        AlertTemplateDefinition template = template(true);
        AlertTemplateParameterDefinition warningCron = new AlertTemplateParameterDefinition(
                template, "warningCron", "cron.label", "cron.description", CronExpression.class.getName(),
                List.of(), true, null, false, 1, true, List.of(AlertParameterSource.TEXT), List.of(), List.of()
        );
        when(templateRepository.findById(7L)).thenReturn(Optional.of(template));
        when(templateParameterRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(7L)).thenReturn(List.of(warningCron));
        when(alertRepository.saveAndFlush(any(Alert.class))).thenAnswer(invocation -> {
            Alert saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 5L);
            return saved;
        });

        assertThatThrownBy(() -> service().create(new AlertCreateRequest(
                7L, "reminder", null, "-", true,
                List.of(new AlertParameterValueRequest("warningCron", AlertParameterSource.TEXT, "not-a-cron", null, null)), Set.of()
        ))).hasMessageContaining("warningCron").hasMessageContaining(CronExpression.class.getName());
    }

    @Test
    void preservesAnExistingSmartPolicyWhenAnUpdateOmitsSmartFields() {
        Alert alert = alert("smart");
        alert.changeSmartExecution(true, 23, SmartExecutionPolicy.NORMAL);
        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert));

        AlertResponse response = service().update(5L, new AlertUpdateRequest(
                0L, "smart", null, "-", true, false, List.of(), Set.of(), null,
                null, null, null
        ));

        assertThat(response.smartExecutionPolicy()).isEqualTo(SmartExecutionPolicy.NORMAL);
        assertThat(response.smartExecutionIntervalHours()).isEqualTo(23);
    }

    @ParameterizedTest
    @CsvSource({ "true,smart", "false,smart", "true,enabled", "false,enabled", "true,binding", "false,binding" })
    void unrelatedAiUpdatesPreserveIssuePersistence(boolean persistent, String field) {
        Alert alert = alert("preserve");
        Instant since = Instant.parse("2026-10-01T00:00:00Z");
        alert.changePersistentIssues(persistent, since);
        alert.changeSmartExecution(true, 23, SmartExecutionPolicy.NORMAL);
        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert));
        List<AiAlertParameterValue> parameters = List.of();
        if ("binding".equals(field)) {
            var definition = requiredSteps(alert.getTemplate());
            when(templateParameterRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(7L)).thenReturn(List.of(definition));
            when(parameterValueRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            var configuration = mock(ApplicationConfiguration.class);
            when(configuration.getId()).thenReturn(23L);
            when(configuration.getValueType()).thenReturn(ConfigurationValueType.STRING);
            when(configurationRepository.findById(23L)).thenReturn(Optional.of(configuration));
            parameters = List.of(new AiAlertParameterValue("steps", AiAlertParameterSource.CONFIGURATION,
                    null, 23L, null, null));
        }
        var request = new AiAlertUpdateRequest(0L, "preserve", null, "-",
                !"enabled".equals(field), false, parameters, Set.of(), null,
                "smart".equals(field) ? false : null, null, null);
        AlertResponse response = service().update(5L, request.toRequest());
        assertThat(response.persistentIssues()).isEqualTo(persistent);
        assertThat(response.persistentIssuesSince()).isEqualTo(persistent ? since : null);
        if ("binding".equals(field))
            assertThat(response.parameters()).singleElement().satisfies(value -> {
                assertThat(value.source()).isEqualTo(AlertParameterSource.CONFIGURATION);
                assertThat(value.configurationId()).isEqualTo(23L);
            });

    }

    @ParameterizedTest
    @CsvSource({ "true,false", "false,true" })
    void explicitAiPersistenceChangesRemainEffective(boolean initial, boolean requested) {
        Alert alert = alert("explicit persistence");
        alert.changePersistentIssues(initial, Instant.parse("2026-10-01T00:00:00Z"));
        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert));
        var request = new AiAlertUpdateRequest(0L, "explicit persistence", null, "-", true, false,
                List.of(), Set.of(), requested, null, null, null);
        AlertResponse response = service().update(5L, request.toRequest());
        assertThat(response.persistentIssues()).isEqualTo(requested);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var serialized = json.readTree(json.writeValueAsString(response));
        assertThat(serialized.path("persistentIssues").booleanValue()).isEqualTo(requested);
        assertThat(serialized.path("persistentIssuesSince").isNull()).isEqualTo(!requested);
    }

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

    @Test
    void runsAnEnabledAlertFromTheDashboard() {
        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert("chequeo-cert")));
        when(eventLogger.currentUsername()).thenReturn("viewer");
        when(executionOrchestrator.trigger(5L, "chequeo-cert", false, AlertExecutionTrigger.MANUAL, "viewer")).thenReturn(true);

        service().runFromDashboard(5L);

        verify(executionOrchestrator).trigger(5L, "chequeo-cert", false, AlertExecutionTrigger.MANUAL, "viewer");
    }

    @Test
    void refusesToRunADisabledAlertFromTheDashboard() {
        Alert alert = alert("chequeo-cert");
        ReflectionTestUtils.setField(alert, "enabled", false);
        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> service().runFromDashboard(5L))
                .isInstanceOfSatisfying(ConflictException.class, exception -> assertThat(exception.getCode()).isEqualTo("ALERT_DISABLED"));

        verify(executionOrchestrator, never()).trigger(anyLong(), any(), anyBoolean(), any(), any());
    }

    private AlertManagementService service() {
        return new AlertManagementService(
                alertRepository, templateRepository, templateParameterRepository, parameterValueRepository,
                executionRepository, stateRepository, configurationRepository, secretRepository,
                procedureRepository, tagRepository, eventLogger, scheduleService, executionOrchestrator, dashboardEventPublisher, mock(ResourceResultObserverService.class), entityManager
        );
    }

    private static Alert alert(String name) {
        AlertTemplateDefinition template = template(false);
        Alert alert = new Alert(template, name, null, "0 0 8 * * *", true, false, Set.of());
        ReflectionTestUtils.setField(alert, "id", 5L);
        return alert;
    }

    private static AlertTemplateDefinition template(boolean persistentIssuesDefault) {
        AlertTemplateDefinition template = new AlertTemplateDefinition(
                "app.alertify.alerts.templates.HttpsCertificateExpiryAlertTemplate",
                "name.key", "description.key", "source/path.java", WorkerCapability.STANDARD,
                persistentIssuesDefault, List.of()
        );
        ReflectionTestUtils.setField(template, "id", 7L);
        return template;
    }

    private static AlertTemplateParameterDefinition requiredSteps(AlertTemplateDefinition template) {
        return new AlertTemplateParameterDefinition(
                template, "steps", "steps.label", "steps.description", String.class.getName(), List.of(), true,
                null, true, 7, true,
                List.of(AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION, AlertParameterSource.SECRET),
                List.of(), List.of()
        );
    }
}
