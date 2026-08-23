package app.alertify.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupComponent implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupComponent.class);

    private final StartupProcess startupProcess;

    public StartupComponent(StartupProcess startupProcess) {
        this.startupProcess = startupProcess;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Running startup process");
        startupProcess.run();
        log.info("Startup process completed");
    }
}
