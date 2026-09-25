package app.alertify.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Spring Boot startup hook that runs required synchronous initialization after
 * the application context has been created.
 */
@Component
public class StartupComponent implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupComponent.class);

    private final StartupProcess startupProcess;
    private final BackendStartupAvailability startupAvailability;

    public StartupComponent(StartupProcess startupProcess, BackendStartupAvailability startupAvailability) {
        this.startupProcess = startupProcess;
        this.startupAvailability = startupAvailability;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Running startup process");
        try {
            startupProcess.run();
            startupAvailability.ready();
            log.info("Startup process completed");
        } catch (RuntimeException | Error exception) {
            startupAvailability.failed();
            throw exception;
        }
    }
}
