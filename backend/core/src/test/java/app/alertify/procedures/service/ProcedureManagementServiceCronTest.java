package app.alertify.procedures.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.InvalidProcedureRequestException;

class ProcedureManagementServiceCronTest {

    @Test
    void acceptsDisabledAndValidCronExpressions() {
        assertThat(ProcedureManagementService.validateCron(" - ")).isEqualTo("-");
        assertThat(ProcedureManagementService.validateCron(" 0 0 2 * * * ")).isEqualTo("0 0 2 * * *");
    }

    @Test
    void rejectsBlankAndInvalidCronExpressions() {
        assertThatThrownBy(() -> ProcedureManagementService.validateCron(" "))
                .isInstanceOf(InvalidProcedureRequestException.class)
                .hasMessage("cronExpression must not be blank");
        assertThatThrownBy(() -> ProcedureManagementService.validateCron("not-a-cron"))
                .isInstanceOf(InvalidProcedureRequestException.class)
                .hasMessageStartingWith("Invalid cron expression:");
    }
}
