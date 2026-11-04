package app.alertify.dashboard;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import app.alertify.realtime.AdminEventPublisher;
import app.alertify.realtime.ViewerEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class DashboardPipeEventPublisher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardPipeEventPublisher.class);
    private final DashboardPipeCardService cards;
    private final AdminEventPublisher admin;
    private final ViewerEventPublisher viewer;
    // Ordered delivery prevents a slower read from overwriting a later lifecycle event.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public DashboardPipeEventPublisher(DashboardPipeCardService cards, AdminEventPublisher admin, ViewerEventPublisher viewer) {
        this.cards = cards;
        this.admin = admin;
        this.viewer = viewer;
    }

    public void changedAfterCommit(long id) { afterCommit(() -> executor.submit(() -> publish(id))); }

    public void removedAfterCommit(long id) {
        afterCommit(() -> executor.submit(() -> {
            admin.publish("DASHBOARD_PIPE_REMOVED", Map.of("pipeId", id));
            viewer.publish("DASHBOARD_PIPE_REMOVED", Map.of("pipeId", id));
        }));
    }

    private void publish(long id) {
        if (!admin.hasAuthenticatedSessions() && !viewer.hasAuthenticatedSessions())
            return;

        try {
            cards.card(id).ifPresent(card -> {
                admin.publish("DASHBOARD_PIPE", card);
                viewer.publish("DASHBOARD_PIPE", card.forViewer());
            });
        } catch (RuntimeException exception) {
            LOGGER.warn("Pipe dashboard publication failed: pipeId={}", id, exception);
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    @Override
    public void close() { executor.close(); }
}
