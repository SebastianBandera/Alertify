package app.alertify.alerts.execution;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import app.alertify.alerts.model.Alert;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.logging.ApplicationEventLogger;

@ExtendWith(MockitoExtension.class)
class AlertScheduleServiceTest {

    @Mock private AlertRepository alertRepository;
    @Mock private AlertExecutionOrchestrator orchestrator;
    @Mock private TaskScheduler taskScheduler;
    @Mock private ApplicationEventLogger eventLogger;
    @Mock private Alert alert;
    @Mock private Alert unschedulableAlert;
    @Mock private ScheduledFuture<?> scheduledFuture;

    private AlertScheduleService service;

    @BeforeEach
    void setUp() {
        service = new AlertScheduleService(
                alertRepository, orchestrator, taskScheduler, eventLogger
        );
    }

    @AfterEach
    void closeService() {
        service.close();
    }

    @Test
    void schedulesEnabledAlertsAtStartupAndRemovesTheirTasksAfterDeletion() {
        when(alertRepository.findAllByEnabledTrue()).thenReturn(List.of(alert));
        when(alert.getId()).thenReturn(7L);
        when(alert.getName()).thenReturn("Sample alert");
        when(alert.getCronExpression()).thenReturn("0 */5 * * * *");
        when(alert.isConcurrentExecutionAllowed()).thenReturn(true);
        doReturn(scheduledFuture).when(taskScheduler)
                .schedule(any(Runnable.class), any(Trigger.class));

        service.scheduleAll();

        ArgumentCaptor<Runnable> scheduledTask = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(scheduledTask.capture(), any(Trigger.class));
        scheduledTask.getValue().run();
        verify(orchestrator).trigger(7L, "Sample alert", true);

        service.removeAfterCommit(7L);

        verify(scheduledFuture).cancel(false);
    }

    @Test
    void keepsSchedulingTheRemainingAlertsWhenOneCannotBeRegistered() {
        when(alertRepository.findAllByEnabledTrue()).thenReturn(List.of(unschedulableAlert, alert));
        when(unschedulableAlert.getId()).thenReturn(3L);
        when(unschedulableAlert.getName()).thenReturn("Never fires");
        when(unschedulableAlert.getCronExpression()).thenReturn("0 0 5 31 2 ?");
        when(alert.getId()).thenReturn(7L);
        when(alert.getName()).thenReturn("Sample alert");
        when(alert.getCronExpression()).thenReturn("0 */5 * * * *");
        // The real scheduler returns null when the trigger has no next execution.
        doReturn(null).doReturn(scheduledFuture).when(taskScheduler)
                .schedule(any(Runnable.class), any(Trigger.class));

        service.scheduleAll();

        verify(eventLogger).failure(eq("ALERT_SCHEDULE_FAILED"), argThat(data -> data.get("alertId").equals(3L)));
        verify(eventLogger).success(eq("ALERT_SCHEDULE_REGISTERED"), argThat(data -> data.get("alertId").equals(7L)));
    }
}
