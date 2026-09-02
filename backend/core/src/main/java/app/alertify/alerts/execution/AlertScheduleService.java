package app.alertify.alerts.execution;

import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import app.alertify.alerts.model.Alert;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.logging.ApplicationEventLogger;

@Service
public class AlertScheduleService implements AutoCloseable {

    private final AlertRepository alertRepository;
    private final AlertExecutionOrchestrator orchestrator;
    private final TaskScheduler taskScheduler;
    private final ApplicationEventLogger eventLogger;
    private final Map<Long, ScheduledFuture<?>> schedules = new ConcurrentHashMap<>();

    public AlertScheduleService(AlertRepository alertRepository, AlertExecutionOrchestrator orchestrator, TaskScheduler taskScheduler, ApplicationEventLogger eventLogger) {
        this.alertRepository = alertRepository;
        this.orchestrator = orchestrator;
        this.taskScheduler = taskScheduler;
        this.eventLogger = eventLogger;
    }

    public synchronized void scheduleAll() {
        schedules.values().forEach(schedule -> schedule.cancel(false));
        schedules.clear();
        for (Alert alert : alertRepository.findAllByEnabledTrue())
            schedule(alert);
    }

    public void rescheduleAfterCommit(Long alertId) {
        afterCommit(() -> reschedule(alertId));
    }

    public void removeAfterCommit(Long alertId) {
        afterCommit(() -> remove(alertId));
    }

    private synchronized void reschedule(Long alertId) {
        remove(alertId);
        alertRepository.findById(alertId)
                .filter(Alert::isEnabled)
                .ifPresent(this::schedule);
    }

    private void schedule(Alert alert) {
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> orchestrator.trigger(
                        alert.getId(), alert.getName(),
                        alert.isConcurrentExecutionAllowed()
                ),
                new CronTrigger(alert.getCronExpression(), ZoneId.systemDefault())
        );
        if (future == null)
            throw new IllegalStateException("Could not schedule alert " + alert.getId());

        schedules.put(alert.getId(), future);
        eventLogger.success("ALERT_SCHEDULE_REGISTERED", Map.of("alertId", alert.getId(), "alertName", alert.getName(), "cronExpression", alert.getCronExpression()));
    }

    private synchronized void remove(Long alertId) {
        ScheduledFuture<?> existing = schedules.remove(alertId);
        if (existing == null)
            return;

        existing.cancel(false);
        eventLogger.success("ALERT_SCHEDULE_REMOVED", Map.of("alertId", alertId));
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
