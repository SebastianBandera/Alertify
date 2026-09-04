package app.alertify.logging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Primary entry point for structured application events. It enriches events
 * with the current actor and request context, writes them to the console and
 * delegates durable persistence without breaking the business operation if
 * logging fails.
 */
@Service
public class ApplicationEventLogger {

    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String REQUEST_PATH_MDC_KEY = "requestPath";

    private static final Logger log = LoggerFactory.getLogger(ApplicationEventLogger.class);

    private final ApplicationLogWriter writer;
    private final String source;

    ApplicationEventLogger(ApplicationLogWriter writer, @Value("${spring.application.name}") String source) {
        this.writer = writer;
        this.source = source;
    }

    /**
     * Username of the current actor, for callers that must carry it into a
     * thread where the security context is no longer available.
     */
    public String currentUsername() {
        return CurrentLogActor.resolve().username();
    }

    public void success(String event, Map<String, ?> data) {
        write(command(ApplicationLogLevel.INFO, event, ApplicationLogOutcome.SUCCESS, data));
    }

    public void failure(String event, Map<String, ?> data) {
        failure(event, ApplicationLogLevel.WARN, data);
    }

    public void failure(String event, ApplicationLogLevel level, Map<String, ?> data) {
        write(command(level, event, ApplicationLogOutcome.FAILURE, data));
    }

    public void error(String event, Map<String, ?> data) {
        write(command(ApplicationLogLevel.ERROR, event, ApplicationLogOutcome.FAILURE, data));
    }

    public void successAfterCommit(String event, Map<String, ?> data) {
        ApplicationLogCommand command = command(
                ApplicationLogLevel.INFO, event, ApplicationLogOutcome.SUCCESS, data
        );
        runAfterCommit(() -> write(command));
    }

    public void failureAfterCommit(String event, Map<String, ?> data) {
        ApplicationLogCommand command = command(
                ApplicationLogLevel.WARN, event, ApplicationLogOutcome.FAILURE, data
        );
        runAfterCommit(() -> write(command));
    }

    public void errorAfterCommit(String event, Map<String, ?> data) {
        ApplicationLogCommand command = command(
                ApplicationLogLevel.ERROR, event, ApplicationLogOutcome.FAILURE, data
        );
        runAfterCommit(() -> write(command));
    }

    private ApplicationLogCommand command(ApplicationLogLevel level, String event, ApplicationLogOutcome outcome, Map<String, ?> data) {
        return new ApplicationLogCommand(
                Instant.now(), level, source, event, outcome, CurrentLogActor.resolve(),
                currentRequestId(), currentRequestPath(), Map.copyOf(data)
        );
    }

    private void runAfterCommit(Runnable action) {
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

    private void write(ApplicationLogCommand command) {
        writeConsole(command);
        try {
            writer.persist(command);
        } catch (RuntimeException exception) {
            log.error("Unable to persist application event={} requestId={}", command.event(), command.requestId(), exception);
        }
    }

    private static void writeConsole(ApplicationLogCommand command) {
        String template = "event={} outcome={} user={} subject={} requestId={} path={} data={}";
        Object[] arguments = {
                command.event(), command.outcome(), command.actor().username(),
                command.actor().subject(), command.requestId(), command.path(), command.data()
        };
        switch (command.level()) {
            case INFO -> log.info(template, arguments);
            case WARN -> log.warn(template, arguments);
            case ERROR -> log.error(template, arguments);
        }
    }

    private static UUID currentRequestId() {
        String requestId = MDC.get(REQUEST_ID_MDC_KEY);
        if (requestId == null)
            return null;

        try {
            return UUID.fromString(requestId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String currentRequestPath() {
        String path = MDC.get(REQUEST_PATH_MDC_KEY);
        return path == null || path.isBlank() ? null : path;
    }
}
