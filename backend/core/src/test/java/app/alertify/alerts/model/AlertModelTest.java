package app.alertify.alerts.model;

import static app.alertify.alerts.execution.AlertExecutionStatus.ERROR;
import static app.alertify.alerts.execution.AlertExecutionStatus.SUCCESS;
import static app.alertify.alerts.execution.AlertExecutionStatus.WARN;
import static app.alertify.alerts.template.annotation.AlertParameterSource.CONFIGURATION;
import static app.alertify.alerts.template.annotation.AlertParameterSource.TEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.worker.contract.WorkerCapability;

class AlertModelTest {

    private static final String TEMPLATE_ID = SampleAlertTemplate.class.getName();

    @Test
    void createsAConfiguredTextParameterWithoutPopulatingReferenceFields() {
        AlertTemplateDefinition template = template();
        AlertTemplateParameterDefinition parameter = new AlertTemplateParameterDefinition(
                template, "endpoint", "endpoint.label", "endpoint.description", String.class.getName(),
                Set.of(TEXT, CONFIGURATION), List.of("google", "cloudflare"), 1, true
        );
        Alert alert = new Alert(template, "Internet", null, "0 */5 * * * *", true);

        AlertParameterValue value = AlertParameterValue.text(alert, parameter, "google");

        assertThat(value.getSource()).isEqualTo(TEXT);
        assertThat(value.getTextValue()).isEqualTo("google");
        assertThat(value.getConfiguration()).isNull();
        assertThat(value.getSecret()).isNull();
    }

    @Test
    void rejectsAParameterSourceThatTheTemplateDoesNotAllow() {
        AlertTemplateDefinition template = template();
        AlertTemplateParameterDefinition parameter = new AlertTemplateParameterDefinition(
                template, "endpoint", "endpoint.label", "endpoint.description", String.class.getName(),
                Set.of(TEXT), List.of(), 1, true
        );
        Alert alert = new Alert(template, "Internet", null, "0 */5 * * * *", true);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> valueWithDisallowedConfiguration(alert, parameter));
    }

    @Test
    void reservesErrorStatusForExceptionExecutions() {
        Alert alert = new Alert(template(), "Internet", null, "0 */5 * * * *", true);
        Instant startedAt = Instant.parse("2026-08-26T12:00:00Z");
        Instant finishedAt = startedAt.plusSeconds(1);

        AlertState state = new AlertState(1L, null);
        state.replaceState("ok");

        assertThat(state.getState()).isEqualTo("ok");
        assertThat(AlertExecution.result(alert, SUCCESS, startedAt, finishedAt, null).getStatus())
                .isEqualTo(SUCCESS);
        assertThat(AlertExecution.result(alert, WARN, startedAt, finishedAt, null).getStatus())
                .isEqualTo(WARN);
        assertThat(AlertExecution.error(alert, startedAt, finishedAt, "java.io.IOException", "offline", "stack").getStatus())
                .isEqualTo(ERROR);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertExecution.result(alert, ERROR, startedAt, finishedAt, null));
    }

    private static void valueWithDisallowedConfiguration(Alert alert, AlertTemplateParameterDefinition parameter) {
        AlertParameterValue.configuration(alert, parameter, null);
    }

    private static AlertTemplateDefinition template() {
        AlertTemplateDefinition template = AlertTemplateDefinition.from(SampleAlertTemplate.class);
        assertThat(template.getId()).isEqualTo(TEMPLATE_ID);
        return template;
    }

    @AlertTemplate(
        nameKey = "internet.name",
        descriptionKey = "internet.description",
        capability = WorkerCapability.STANDARD
    )
    private static final class SampleAlertTemplate {
    }
}
