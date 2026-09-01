package app.alertify.worker.standard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import app.alertify.worker.runtime.WorkerRuntimeConfiguration;

/**
 * Starts the standard worker as an independent Spring Boot process. The worker
 * exposes the shared gRPC execution runtime with the standard capability.
 */
@SpringBootApplication
@Import(WorkerRuntimeConfiguration.class)
public class WorkerStandardApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerStandardApplication.class, args);
    }
}
