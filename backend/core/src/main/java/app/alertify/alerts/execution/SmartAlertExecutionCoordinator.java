package app.alertify.alerts.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertExecution;
import app.alertify.alerts.model.SmartExecutionPolicy;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertRepository;

/** Best-effort coordinator for capacity-aware alert executions. */
@Service
public class SmartAlertExecutionCoordinator implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmartAlertExecutionCoordinator.class);
    private static final Duration RETRY_POLL_INTERVAL = Duration.ofSeconds(5);

    private final AlertRepository alertRepository;
    private final AlertExecutionRepository executionRepository;
    private final AlertExecutionOrchestrator orchestrator;
    private final CronQuietHoursService quietHoursService;
    private final MaintenanceModeService maintenanceModeService;
    private final SmartExecutionProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Clock clock = Clock.systemUTC();

    public SmartAlertExecutionCoordinator(AlertRepository alertRepository, AlertExecutionRepository executionRepository, AlertExecutionOrchestrator orchestrator, CronQuietHoursService quietHoursService, MaintenanceModeService maintenanceModeService, SmartExecutionProperties properties) {
        this.alertRepository = alertRepository;
        this.executionRepository = executionRepository;
        this.orchestrator = orchestrator;
        this.quietHoursService = quietHoursService;
        this.maintenanceModeService = maintenanceModeService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void afterApplicationReady() {
        launchIfIdle();
    }

    @Scheduled(
        fixedRateString = "${alert.smart-execution.scan-interval:10m}",
        initialDelayString = "${alert.smart-execution.scan-interval:10m}"
    )
    void scheduledScan() {
        launchIfIdle();
    }

    void launchIfIdle() {
        if (!running.compareAndSet(false, true))
            return;

        try {
            executor.submit(() -> {
                try {
                    runCycle();
                } catch (RuntimeException exception) {
                    LOGGER.error("Smart alert execution cycle failed", exception);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    void runCycle() {
        if (blocked())
            return;

        List<Candidate> pending = candidates();
        Instant deadline = Instant.now(clock).plus(properties.capacityWaitTimeout());
        while (!pending.isEmpty()) {
            if (blocked())
                return;

            Iterator<Candidate> iterator = pending.iterator();
            while (iterator.hasNext()) {
                Candidate candidate = iterator.next();
                Optional<Alert> current = alertRepository.findById(candidate.alertId());
                if (current.isEmpty() || !eligible(current.get(), Instant.now(clock))) {
                    iterator.remove();
                    continue;
                }

                Alert alert = current.get();
                AlertExecutionOrchestrator.SmartTriggerResult result = orchestrator.triggerSmart(
                        alert.getId(), alert.getName(), alert.getTemplate().getRequiredCapability()
                );
                if (result == AlertExecutionOrchestrator.SmartTriggerResult.ACCEPTED
                        || result == AlertExecutionOrchestrator.SmartTriggerResult.NO_WORKER
                        || result == AlertExecutionOrchestrator.SmartTriggerResult.BLOCKED) {
                    iterator.remove();
                }
            }

            if (pending.isEmpty())
                return;

            Instant now = Instant.now(clock);
            if (!now.isBefore(deadline))
                return;

            Duration remaining = Duration.between(now, deadline);
            try {
                Thread.sleep(remaining.compareTo(RETRY_POLL_INTERVAL) < 0 ? remaining : RETRY_POLL_INTERVAL);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<Candidate> candidates() {
        List<Candidate> candidates = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (Alert alert : alertRepository.findAllByEnabledTrueAndSmartExecutionEnabledTrue()) {
            if (!eligible(alert, now))
                continue;

            Instant lastSmartExecution = executionRepository
                    .findFirstByAlert_IdAndTriggerAndFinishedAtIsNotNullOrderByFinishedAtDescIdDesc(alert.getId(), AlertExecutionTrigger.SMART)
                    .map(AlertExecution::getFinishedAt)
                    .orElse(null);
            candidates.add(new Candidate(alert.getId(), lastSmartExecution));
        }
        candidates.sort(Comparator
                .comparing(Candidate::lastSmartExecution, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Candidate::alertId));
        return candidates;
    }

    private boolean eligible(Alert alert, Instant now) {
        if (!alert.isEnabled() || !alert.isSmartExecutionEnabled())
            return false;

        Integer intervalHours = alert.getSmartExecutionIntervalHours();
        SmartExecutionPolicy policy = alert.getSmartExecutionPolicy();
        if (intervalHours == null || intervalHours < 1 || policy == null)
            return false;

        Instant threshold = now.minus(Duration.ofHours(intervalHours.longValue()));
        if (executionRepository.existsByAlert_IdAndStatusAndFinishedAtGreaterThanEqual(alert.getId(), AlertExecutionStatus.SUCCESS, threshold))
            return false;

        if (policy == SmartExecutionPolicy.NORMAL)
            return true;

        Optional<AlertExecution> latest = executionRepository.findFirstByAlert_IdAndFinishedAtIsNotNullOrderByFinishedAtDescIdDesc(alert.getId());
        if (latest.isEmpty())
            return false;

        return switch (policy) {
            case NORMAL -> true;
            case ON_ERROR -> latest.get().getStatus() == AlertExecutionStatus.ERROR;
            case ON_WARN -> latest.get().getStatus() == AlertExecutionStatus.WARN;
            case ON_ERROR_OR_WARN -> latest.get().getStatus() == AlertExecutionStatus.ERROR
                    || latest.get().getStatus() == AlertExecutionStatus.WARN;
        };
    }

    private boolean blocked() {
        return quietHoursService.isQuietNow() || maintenanceModeService.isActive();
    }

    void setClockForTesting(Clock clock) {
        this.clock = clock;
    }

    boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        executor.close();
    }

    private record Candidate(Long alertId, Instant lastSmartExecution) {
    }
}
