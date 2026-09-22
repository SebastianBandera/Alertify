package app.alertify.dashboard;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import app.alertify.realtime.AdminEventPublisher;
import app.alertify.realtime.ViewerEventPublisher;

/**
 * Publishes dashboard tiles through the role-specific event channels whenever
 * an alert or its execution state changes. Each event carries the complete
 * tile, so clients merge it by alert id regardless of what they already hold.
 */
@Service
public class DashboardEventPublisher implements AutoCloseable {

    static final String ALERT_EVENT = "DASHBOARD_ALERT";
    static final String ALERT_REMOVED_EVENT = "DASHBOARD_ALERT_REMOVED";
    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardEventPublisher.class);

    private final AdminEventPublisher eventPublisher;
    private final ViewerEventPublisher viewerEventPublisher;
    private final DashboardCardService cardService;
    private final AlertExecutionRunningRegistry runningRegistry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DashboardEventPublisher(AdminEventPublisher eventPublisher, ViewerEventPublisher viewerEventPublisher, DashboardCardService cardService, AlertExecutionRunningRegistry runningRegistry) {
        this.eventPublisher = eventPublisher;
        this.viewerEventPublisher = viewerEventPublisher;
        this.cardService = cardService;
        this.runningRegistry = runningRegistry;
    }

    public void executionStarted(long alertId, UUID executionId, Instant startedAt) {
        runningRegistry.started(alertId, executionId, startedAt);
        publishCard(alertId);
    }

    /** Called once the outcome is persisted (or persistence gave up), so the tile reads the fresh result. */
    public void executionFinished(long alertId, UUID executionId) {
        runningRegistry.finished(alertId, executionId);
        publishCard(alertId);
    }

    public void alertChangedAfterCommit(long alertId) {
        afterCommit(() -> publishCard(alertId));
    }

    public void alertRemovedAfterCommit(long alertId) {
        afterCommit(() -> publish(ALERT_REMOVED_EVENT, new AlertRemovedPayload(alertId)));
    }

    private void publishCard(long alertId) {
        if (!hasAuthenticatedSessions())
            return;

        executor.submit(() -> {
            try {
                cardService.card(alertId).ifPresent(card -> publish(ALERT_EVENT, card));
            } catch (RuntimeException exception) {
                LOGGER.warn("Dashboard tile publication failed: alertId={}", alertId, exception);
            }
        });
    }

    private void publish(String eventName, Object payload) {
        if (eventPublisher.hasAuthenticatedSessions())
            eventPublisher.publish(eventName, payload);

        if (viewerEventPublisher.hasAuthenticatedSessions())
            viewerEventPublisher.publish(eventName, payload);
    }

    private boolean hasAuthenticatedSessions() {
        return eventPublisher.hasAuthenticatedSessions() || viewerEventPublisher.hasAuthenticatedSessions();
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }

        action.run();
    }

    @Override
    public void close() {
        executor.close();
    }

    record AlertRemovedPayload(long alertId) { }
}
