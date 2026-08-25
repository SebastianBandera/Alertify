package app.alertify.grpc;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the shared DNS discovery settings used for every worker replica,
 * independently of the capabilities advertised by each node.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerGrpcProperties.class)
class WorkerGrpcConfiguration {
}
