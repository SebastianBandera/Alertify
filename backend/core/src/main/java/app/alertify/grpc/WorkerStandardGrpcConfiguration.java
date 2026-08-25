package app.alertify.grpc;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the external settings used by standard worker DNS discovery and
 * direct per-replica gRPC health probes.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerStandardGrpcProperties.class)
class WorkerStandardGrpcConfiguration {
}
