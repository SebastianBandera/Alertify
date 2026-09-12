package app.alertify.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.InvalidAlertRequestException;

class AlertManagementServiceCronTest {

    @Test
    void acceptsValidCronExpressions() {
        assertThat(AlertManagementService.validateCron(" 0 0 2 * * * ")).isEqualTo("0 0 2 * * *");
    }

    @Test
    void rejectsBlankAndInvalidCronExpressions() {
        assertThatThrownBy(() -> AlertManagementService.validateCron(" "))
                .isInstanceOf(InvalidAlertRequestException.class)
                .hasMessage("cronExpression must not be blank");
        assertThatThrownBy(() -> AlertManagementService.validateCron("not-a-cron"))
                .isInstanceOf(InvalidAlertRequestException.class)
                .hasMessageStartingWith("Invalid cron expression:");
    }

    @Test
    void rejectsCronExpressionsThatNeverFire() {
        assertThatThrownBy(() -> AlertManagementService.validateCron("0 0 5 31 2 ?"))
                .isInstanceOf(InvalidAlertRequestException.class)
                .hasMessageContaining("never matches a future date");
    }
}
