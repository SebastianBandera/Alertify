package app.alertify.alerts.execution;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("alert.smart-execution")
public record SmartExecutionProperties(Duration scanInterval, Duration capacityWaitTimeout) {

    private static final Duration MINIMUM = Duration.ofMinutes(1);

    public SmartExecutionProperties {
        requireMinimum("alert.smart-execution.scan-interval", scanInterval);
        requireMinimum("alert.smart-execution.capacity-wait-timeout", capacityWaitTimeout);
    }

    private static void requireMinimum(String name, Duration value) {
        if (value == null || value.compareTo(MINIMUM) < 0)
            throw new IllegalArgumentException(name + " must be at least 1 minute");
    }
}
