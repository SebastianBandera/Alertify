package app.alertify.procedures.execution;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProcedureExecutionProperties.class)
class ProcedureExecutionConfiguration {
}
