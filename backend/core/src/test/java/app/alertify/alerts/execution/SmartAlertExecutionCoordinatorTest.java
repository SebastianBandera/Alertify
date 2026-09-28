package app.alertify.alerts.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertExecution;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.SmartExecutionPolicy;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.worker.contract.WorkerCapability;

@ExtendWith(MockitoExtension.class)
class SmartAlertExecutionCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    @Mock private AlertRepository alertRepository;
    @Mock private AlertExecutionRepository executionRepository;
    @Mock private AlertExecutionOrchestrator orchestrator;
    @Mock private CronQuietHoursService quietHoursService;
    @Mock private MaintenanceModeService maintenanceModeService;
    @Mock private Alert alert;
    @Mock private AlertTemplateDefinition template;
    @Mock private AlertExecution latest;

    private SmartAlertExecutionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new SmartAlertExecutionCoordinator(
                alertRepository, executionRepository, orchestrator, quietHoursService, maintenanceModeService,
                new SmartExecutionProperties(Duration.ofMinutes(10), Duration.ofMinutes(10))
        );
        coordinator.setClockForTesting(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void close() {
        coordinator.close();
    }

    @Test
    void normalPolicyRunsWithoutHistoryWhenNoRecentSuccessExists() {
        smartAlert(SmartExecutionPolicy.NORMAL);
        when(orchestrator.triggerSmart(7L, "Sample", WorkerCapability.STANDARD))
                .thenReturn(AlertExecutionOrchestrator.SmartTriggerResult.ACCEPTED);

        coordinator.runCycle();

        verify(orchestrator).triggerSmart(7L, "Sample", WorkerCapability.STANDARD);
    }

    @Test
    void oncePerIntervalRunsWithoutHistory() {
        smartAlert(SmartExecutionPolicy.ONCE_PER_INTERVAL);
        when(orchestrator.triggerSmart(7L, "Sample", WorkerCapability.STANDARD))
                .thenReturn(AlertExecutionOrchestrator.SmartTriggerResult.ACCEPTED);

        coordinator.runCycle();

        verify(orchestrator).triggerSmart(7L, "Sample", WorkerCapability.STANDARD);
    }

    @Test
    void oncePerIntervalWaitsAfterAnyRecentCompletedExecution() {
        smartAlert(SmartExecutionPolicy.ONCE_PER_INTERVAL);
        when(latest.getFinishedAt()).thenReturn(NOW.minus(Duration.ofHours(1)));
        when(executionRepository.findFirstByAlert_IdAndFinishedAtIsNotNullOrderByFinishedAtDescIdDesc(7L)).thenReturn(Optional.of(latest));

        coordinator.runCycle();

        verify(orchestrator, never()).triggerSmart(7L, "Sample", WorkerCapability.STANDARD);
    }

    @Test
    void oncePerIntervalWaitsAtTheExactIntervalBoundary() {
        smartAlert(SmartExecutionPolicy.ONCE_PER_INTERVAL);
        when(latest.getFinishedAt()).thenReturn(NOW.minus(Duration.ofHours(2)));
        when(executionRepository.findFirstByAlert_IdAndFinishedAtIsNotNullOrderByFinishedAtDescIdDesc(7L)).thenReturn(Optional.of(latest));

        coordinator.runCycle();

        verify(orchestrator, never()).triggerSmart(7L, "Sample", WorkerCapability.STANDARD);
    }

    @Test
    void oncePerIntervalRunsAfterTheLatestCompletedExecutionExpires() {
        smartAlert(SmartExecutionPolicy.ONCE_PER_INTERVAL);
        when(latest.getFinishedAt()).thenReturn(NOW.minus(Duration.ofHours(2)).minusSeconds(1));
        when(executionRepository.findFirstByAlert_IdAndFinishedAtIsNotNullOrderByFinishedAtDescIdDesc(7L)).thenReturn(Optional.of(latest));
        when(orchestrator.triggerSmart(7L, "Sample", WorkerCapability.STANDARD))
                .thenReturn(AlertExecutionOrchestrator.SmartTriggerResult.ACCEPTED);

        coordinator.runCycle();

        verify(orchestrator).triggerSmart(7L, "Sample", WorkerCapability.STANDARD);
    }

    @Test
    void conditionalPolicyWaitsForItsFirstResult() {
        smartAlert(SmartExecutionPolicy.ON_ERROR_OR_WARN);
        when(executionRepository.findFirstByAlert_IdAndFinishedAtIsNotNullOrderByFinishedAtDescIdDesc(7L)).thenReturn(Optional.empty());

        coordinator.runCycle();

        verify(orchestrator, never()).triggerSmart(7L, "Sample", WorkerCapability.STANDARD);
    }

    @Test
    void errorPolicyRunsOnlyForTheLatestError() {
        smartAlert(SmartExecutionPolicy.ON_ERROR);
        when(latest.getStatus()).thenReturn(AlertExecutionStatus.ERROR);
        when(executionRepository.findFirstByAlert_IdAndFinishedAtIsNotNullOrderByFinishedAtDescIdDesc(7L)).thenReturn(Optional.of(latest));
        when(orchestrator.triggerSmart(7L, "Sample", WorkerCapability.STANDARD))
                .thenReturn(AlertExecutionOrchestrator.SmartTriggerResult.ACCEPTED);

        coordinator.runCycle();

        verify(orchestrator).triggerSmart(7L, "Sample", WorkerCapability.STANDARD);
    }

    @Test
    void recentSuccessSuppressesEveryPolicy() {
        smartAlert(SmartExecutionPolicy.ON_ERROR_OR_WARN);
        when(executionRepository.existsByAlert_IdAndStatusAndFinishedAtGreaterThanEqual(
                7L, AlertExecutionStatus.SUCCESS, NOW.minus(Duration.ofHours(2))
        )).thenReturn(true);

        coordinator.runCycle();

        verify(orchestrator, never()).triggerSmart(7L, "Sample", WorkerCapability.STANDARD);
    }

    @Test
    void maintenancePreventsTheCycleFromReadingAlerts() {
        when(maintenanceModeService.isActive()).thenReturn(true);

        coordinator.runCycle();

        verifyNoInteractions(alertRepository, executionRepository, orchestrator);
    }

    @Test
    void unavailableWorkersAreOmittedWithoutWaiting() {
        smartAlert(SmartExecutionPolicy.NORMAL);
        when(orchestrator.triggerSmart(7L, "Sample", WorkerCapability.STANDARD))
                .thenReturn(AlertExecutionOrchestrator.SmartTriggerResult.NO_WORKER);

        coordinator.runCycle();

        verify(orchestrator).triggerSmart(7L, "Sample", WorkerCapability.STANDARD);
        assertThat(coordinator.isRunning()).isFalse();
    }

    private void smartAlert(SmartExecutionPolicy policy) {
        org.mockito.Mockito.lenient().when(alertRepository.findAllByEnabledTrueAndSmartExecutionEnabledTrue()).thenReturn(List.of(alert));
        org.mockito.Mockito.lenient().when(alertRepository.findById(7L)).thenReturn(Optional.of(alert));
        org.mockito.Mockito.lenient().when(alert.getId()).thenReturn(7L);
        org.mockito.Mockito.lenient().when(alert.getName()).thenReturn("Sample");
        org.mockito.Mockito.lenient().when(alert.isEnabled()).thenReturn(true);
        org.mockito.Mockito.lenient().when(alert.isSmartExecutionEnabled()).thenReturn(true);
        org.mockito.Mockito.lenient().when(alert.getSmartExecutionIntervalHours()).thenReturn(2);
        org.mockito.Mockito.lenient().when(alert.getSmartExecutionPolicy()).thenReturn(policy);
        org.mockito.Mockito.lenient().when(alert.getTemplate()).thenReturn(template);
        org.mockito.Mockito.lenient().when(template.getRequiredCapability()).thenReturn(WorkerCapability.STANDARD);
        org.mockito.Mockito.lenient().when(executionRepository.findFirstByAlert_IdAndTriggerAndFinishedAtIsNotNullOrderByFinishedAtDescIdDesc(7L, AlertExecutionTrigger.SMART))
                .thenReturn(Optional.empty());
    }
}
