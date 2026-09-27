package app.alertify.alerts.execution;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmartExecutionProperties.class)
class SmartExecutionConfiguration {
}
