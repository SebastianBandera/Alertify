package app.alertify.worker.playwright;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import app.alertify.worker.runtime.WorkerRuntimeConfiguration;

/**
 * Starts the Playwright-capable worker as an independent Spring Boot process.
 * Browser automation is loaded only by alert templates that require it.
 */
@SpringBootApplication
@Import(WorkerRuntimeConfiguration.class)
public class WorkerPlaywrightApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerPlaywrightApplication.class, args);
    }
}
