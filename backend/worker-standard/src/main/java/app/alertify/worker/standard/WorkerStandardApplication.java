package app.alertify.worker.standard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import app.alertify.worker.standard.grpc.WorkerStandardGrpcServerProperties;

/**
 * Starts the standard worker as an independent Spring Boot process. The worker
 * currently exposes the standard gRPC health service and is ready to host
 * alert execution services later.
 */
@SpringBootApplication
@EnableConfigurationProperties(WorkerStandardGrpcServerProperties.class)
public class WorkerStandardApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerStandardApplication.class, args);
    }
}
