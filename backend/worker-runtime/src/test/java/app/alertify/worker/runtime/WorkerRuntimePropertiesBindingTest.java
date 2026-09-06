package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import app.alertify.worker.contract.WorkerCapability;

class WorkerRuntimePropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "alertify.worker.name=test-worker",
                    "alertify.worker.grpc-port=9090",
                    "alertify.worker.shutdown-grace-period=10s",
                    "alertify.worker.capabilities=STANDARD",
                    "alertify.worker.max-concurrent-alerts=8",
                    "alertify.worker.compiler-output-directory=target/compiled",
                    "alertify.worker.compiler-classpath-directory=target/classpath",
                    "alertify.worker.tls.enabled=false"
            );

    @Test
    void bindsWorkerConfigurationWithoutAReverseDispatcher() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            WorkerRuntimeProperties properties = context.getBean(WorkerRuntimeProperties.class);
            assertThat(properties.name()).isEqualTo("test-worker");
            assertThat(properties.shutdownGracePeriod()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.capabilities()).containsExactly(WorkerCapability.STANDARD);
            assertThat(properties.grpcPort()).isEqualTo(9090);
            assertThat(properties.tls().enabled()).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WorkerRuntimeProperties.class)
    static class PropertiesConfiguration {
    }
}
