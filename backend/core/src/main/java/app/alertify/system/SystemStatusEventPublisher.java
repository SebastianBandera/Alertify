package app.alertify.system;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import app.alertify.realtime.AdminEventPublisher;
import app.alertify.realtime.AdminSessionAuthenticatedEvent;
import app.alertify.system.api.SystemStatusSummaryResponse;

/** Produces system-status snapshots for the general administrative event channel. */
@Service
public class SystemStatusEventPublisher implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemStatusEventPublisher.class);
    private static final String SYSTEM_STATUS_EVENT = "SYSTEM_STATUS";

    private final AdminEventPublisher eventPublisher;
    private final SystemStatusService systemStatusService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean pending = new AtomicBoolean();
    private SystemStatusSummaryResponse lastPublishedSummary;

    public SystemStatusEventPublisher(AdminEventPublisher eventPublisher, SystemStatusService systemStatusService) {
        this.eventPublisher = eventPublisher;
        this.systemStatusService = systemStatusService;
    }

    public void publish() {
        if (!eventPublisher.hasAuthenticatedSessions() || !pending.compareAndSet(false, true))
            return;

        executor.submit(() -> {
            try {
                publishCurrentStatus(true);
            } finally {
                pending.set(false);
            }
        });
    }

    /** Reconciles the complete snapshot because execution events can race worker registration. */
    @Scheduled(fixedDelay = 5_000)
    public void publishWhenSystemStatusChanges() {
        if (eventPublisher.hasAuthenticatedSessions())
            publishCurrentStatus(false);
    }

    @EventListener
    public void publishInitialStatus(AdminSessionAuthenticatedEvent event) {
        executor.submit(() -> publishCurrentStatusTo(event.sessionId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterSystemConfigurationChange(SystemConfigurationChangedEvent event) {
        if (event.maintenanceModeChanged() || event.cronQuietHoursChanged())
            publish();
    }

    @EventListener
    public void publishAfterCronQuietHoursTransition(CronQuietHoursTransitionEvent event) {
        publish();
    }

    private synchronized void publishCurrentStatus(boolean force) {
        try {
            SystemStatusSummaryResponse summary = systemStatusService.realtimeSummary();
            if (!force && summary.equals(lastPublishedSummary))
                return;

            lastPublishedSummary = summary;
            eventPublisher.publish(SYSTEM_STATUS_EVENT, summary);
        } catch (RuntimeException exception) {
            LOGGER.warn("Administrative system-status publication failed", exception);
        }
    }

    private void publishCurrentStatusTo(String sessionId) {
        try {
            eventPublisher.publishTo(sessionId, SYSTEM_STATUS_EVENT, systemStatusService.realtimeSummary());
        } catch (RuntimeException exception) {
            LOGGER.warn("Initial administrative system-status publication failed", exception);
        }
    }

    @Override
    public void close() {
        executor.close();
    }
}
