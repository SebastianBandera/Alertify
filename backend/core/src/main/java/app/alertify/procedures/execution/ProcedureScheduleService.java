package app.alertify.procedures.execution;

import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.model.Procedure;

@Service
public class ProcedureScheduleService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProcedureScheduleService.class);

    private final ProcedureRepository procedureRepository;
    private final ProcedureExecutionOrchestrator orchestrator;
    private final TaskScheduler taskScheduler;
    private final ApplicationEventLogger eventLogger;
    private final Map<Long, ScheduledFuture<?>> schedules = new ConcurrentHashMap<>();

    public ProcedureScheduleService(ProcedureRepository procedureRepository, ProcedureExecutionOrchestrator orchestrator, TaskScheduler taskScheduler, ApplicationEventLogger eventLogger) {
        this.procedureRepository = procedureRepository;
        this.orchestrator = orchestrator;
        this.taskScheduler = taskScheduler;
        this.eventLogger = eventLogger;
    }

    public synchronized void scheduleAll() {
        schedules.values().forEach(schedule -> schedule.cancel(false));
        schedules.clear();
        for (Procedure procedure : procedureRepository.findAllByEnabledTrue()) {
            try {
                schedule(procedure);
            } catch (RuntimeException exception) {
                // Keep the application starting: a single unschedulable procedure is reported, not fatal.
                log.error("Procedure {} ({}) could not be scheduled and stays inactive: {}", procedure.getId(), procedure.getName(), exception.getMessage());
                eventLogger.failure("PROCEDURE_SCHEDULE_FAILED", Map.of(
                        "procedureId", procedure.getId(), "procedureName", procedure.getName(),
                        "cronExpression", procedure.getCronExpression(), "reason", String.valueOf(exception.getMessage())
                ));
            }
        }
    }

    public void rescheduleAfterCommit(Long procedureId) {
        afterCommit(() -> reschedule(procedureId));
    }

    public void removeAfterCommit(Long procedureId) {
        afterCommit(() -> remove(procedureId));
    }

    private synchronized void reschedule(Long procedureId) {
        remove(procedureId);
        procedureRepository.findById(procedureId)
                .filter(Procedure::isEnabled)
                .ifPresent(this::schedule);
    }

    private void schedule(Procedure procedure) {
        if (Scheduled.CRON_DISABLED.equals(procedure.getCronExpression()))
            return;

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> orchestrator.triggerCron(procedure.getId(), procedure.getName(), procedure.isConcurrentExecutionAllowed()),
                new CronTrigger(procedure.getCronExpression(), ZoneId.systemDefault())
        );
        if (future == null)
            throw new IllegalStateException("Could not schedule procedure " + procedure.getId());

        schedules.put(procedure.getId(), future);
        eventLogger.success("PROCEDURE_SCHEDULE_REGISTERED", Map.of("procedureId", procedure.getId(), "procedureName", procedure.getName(), "cronExpression", procedure.getCronExpression()));
    }

    private synchronized void remove(Long procedureId) {
        ScheduledFuture<?> existing = schedules.remove(procedureId);
        if (existing == null)
            return;

        existing.cancel(false);
        eventLogger.success("PROCEDURE_SCHEDULE_REMOVED", Map.of("procedureId", procedureId));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    }
            );
            return;
        }
        action.run();
    }

    @Override
    public synchronized void close() {
        schedules.values().forEach(schedule -> schedule.cancel(false));
        schedules.clear();
    }
}
