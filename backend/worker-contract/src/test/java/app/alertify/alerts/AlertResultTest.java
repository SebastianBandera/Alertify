package app.alertify.alerts;

import static app.alertify.alerts.execution.AlertExecutionStatus.ERROR;
import static app.alertify.alerts.execution.AlertExecutionStatus.SUCCESS;
import static app.alertify.alerts.execution.AlertExecutionStatus.WARN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AlertResultTest {

    @Test
    void createsSuccessAndWarnResultsWithDefensiveMessages() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("statusCode", 204);

        AlertResult success = AlertResult.success(message);
        AlertResult warn = AlertResult.warn(Map.of("statusCode", 503));
        message.put("latencyMs", 12);

        assertThat(success.status()).isEqualTo(SUCCESS);
        assertThat(success.statusMessage()).hasSize(1);
        assertThat(success.statusMessage().get("statusCode")).isEqualTo(204);
        assertThat(warn.status()).isEqualTo(WARN);
        assertThatThrownBy(() -> success.statusMessage().put("other", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsErrorAsANormalResult() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AlertResult(ERROR, Map.of()));
    }
}
