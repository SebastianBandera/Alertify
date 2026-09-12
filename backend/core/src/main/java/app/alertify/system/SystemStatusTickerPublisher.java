package app.alertify.system;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.context.event.EventListener;

/** Coalesces nearby execution changes into one live status publication. */
@Service
public class SystemStatusTickerPublisher implements AutoCloseable {

    private final SystemStatusTickerWebSocketHandler handler;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean pending = new AtomicBoolean();

    public SystemStatusTickerPublisher(SystemStatusTickerWebSocketHandler handler) {
        this.handler = handler;
    }

    public void publish() {
        if (!handler.hasAuthenticatedSessions() || !pending.compareAndSet(false, true))
            return;

        executor.submit(() -> {
            try {
                handler.publishCurrentStatus();
            } finally {
                pending.set(false);
            }
        });
    }

    /** Publishes a new snapshot only when live worker queues have changed. */
    @Scheduled(fixedDelay = 5_000)
    public void publishWhenWorkerQueuesChange() {
        if (handler.hasAuthenticatedSessions())
            handler.publishCurrentStatusIfChanged();
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

    @Override
    public void close() {
        executor.close();
    }
}
