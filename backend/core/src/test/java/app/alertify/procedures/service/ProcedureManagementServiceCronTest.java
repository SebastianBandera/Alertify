package app.alertify.procedures.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.InvalidProcedureRequestException;

class ProcedureManagementServiceCronTest {

    @Test
    void acceptsDisabledBlankAndValidCronExpressions() {
        assertThat(ProcedureManagementService.validateCron(" - ")).isEqualTo("-");
        assertThat(ProcedureManagementService.validateCron(" ")).isEqualTo("-");
        assertThat(ProcedureManagementService.validateCron(" 0 0 2 * * * ")).isEqualTo("0 0 2 * * *");
    }

    @Test
    void rejectsNullAndInvalidCronExpressions() {
        assertThatThrownBy(() -> ProcedureManagementService.validateCron(null))
                .isInstanceOf(InvalidProcedureRequestException.class)
                .hasMessage("cronExpression must not be null");
        assertThatThrownBy(() -> ProcedureManagementService.validateCron("not-a-cron"))
                .isInstanceOf(InvalidProcedureRequestException.class)
                .hasMessageStartingWith("Invalid cron expression:");
    }

    @Test
    void rejectsCronExpressionsThatNeverFire() {
        assertThatThrownBy(() -> ProcedureManagementService.validateCron("0 0 5 31 2 ?"))
                .isInstanceOf(InvalidProcedureRequestException.class)
                .hasMessageContaining("never matches a future date");
    }
}
