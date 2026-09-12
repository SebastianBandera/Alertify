package app.alertify.procedures.execution;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.model.Procedure;

@ExtendWith(MockitoExtension.class)
class ProcedureScheduleServiceTest {

    @Mock private ProcedureRepository procedureRepository;
    @Mock private ProcedureExecutionOrchestrator orchestrator;
    @Mock private TaskScheduler taskScheduler;
    @Mock private ApplicationEventLogger eventLogger;
    @Mock private Procedure procedure;
    @Mock private ScheduledFuture<?> scheduledFuture;

    private ProcedureScheduleService service;

    @BeforeEach
    void setUp() {
        service = new ProcedureScheduleService(procedureRepository, orchestrator, taskScheduler, eventLogger);
    }

    @AfterEach
    void closeService() {
        service.close();
    }

    @Test
    void schedulesEnabledProceduresAtStartupAndRemovesTheirTasksAfterDeletion() {
        when(procedureRepository.findAllByEnabledTrue()).thenReturn(List.of(procedure));
        when(procedure.getId()).thenReturn(7L);
        when(procedure.getName()).thenReturn("Database backup");
        when(procedure.getCronExpression()).thenReturn("0 0 2 * * *");
        when(procedure.isConcurrentExecutionAllowed()).thenReturn(false);
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));

        service.scheduleAll();

        ArgumentCaptor<Runnable> scheduledTask = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(scheduledTask.capture(), any(Trigger.class));
        scheduledTask.getValue().run();
        verify(orchestrator).triggerCron(7L, "Database backup", false);

        service.removeAfterCommit(7L);

        verify(scheduledFuture).cancel(false);
    }

    @Test
    void doesNotScheduleTheDisabledCronMarker() {
        when(procedureRepository.findAllByEnabledTrue()).thenReturn(List.of(procedure));
        when(procedure.getCronExpression()).thenReturn("-");

        service.scheduleAll();

        verifyNoInteractions(taskScheduler);
    }
}
