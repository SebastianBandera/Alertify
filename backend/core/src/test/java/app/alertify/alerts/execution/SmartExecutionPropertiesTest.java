package app.alertify.alerts.execution;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SmartExecutionPropertiesTest {

    @Test
    void acceptsOneMinuteAsTheMinimum() {
        assertThatNoException().isThrownBy(() -> new SmartExecutionProperties(Duration.ofMinutes(1), Duration.ofMinutes(1)));
    }

    @Test
    void rejectsShorterDurations() {
        assertThatThrownBy(() -> new SmartExecutionProperties(Duration.ofSeconds(59), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scan-interval");
        assertThatThrownBy(() -> new SmartExecutionProperties(Duration.ofMinutes(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity-wait-timeout");
    }
}
