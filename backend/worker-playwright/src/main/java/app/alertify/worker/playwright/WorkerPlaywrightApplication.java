package app.alertify.worker.playwright;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import app.alertify.worker.playwright.grpc.WorkerPlaywrightGrpcServerProperties;

/**
 * Starts the Playwright-capable worker as an independent Spring Boot process.
 * Browser automation is intentionally not initialized until execution support
 * is implemented.
 */
@SpringBootApplication
@EnableConfigurationProperties(WorkerPlaywrightGrpcServerProperties.class)
public class WorkerPlaywrightApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerPlaywrightApplication.class, args);
    }
}
