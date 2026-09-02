package app.alertify.alerts.model;

import static app.alertify.alerts.execution.AlertExecutionStatus.ERROR;
import static app.alertify.alerts.execution.AlertExecutionStatus.SUCCESS;
import static app.alertify.alerts.execution.AlertExecutionStatus.WARN;
import static app.alertify.alerts.template.annotation.AlertParameterSource.TEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.worker.contract.WorkerCapability;

class AlertModelTest {

    private static final String TEMPLATE_KEY = SampleAlertTemplate.class.getName();

    @Test
    void createsAConfiguredTextParameterWithoutPopulatingReferenceFields() {
        AlertTemplateDefinition template = template();
        AlertTemplateParameterDefinition parameter = new AlertTemplateParameterDefinition(
                template, "endpoint", "endpoint.label", "endpoint.description", String.class.getName(),
                List.of("google", "cloudflare"), true, "google", 1, true
        );
        Alert alert = new Alert(template, "Internet", null, "0 */5 * * * *", true);

        AlertParameterValue value = AlertParameterValue.text(alert, parameter, "google");

        assertThat(value.getSource()).isEqualTo(TEXT);
        assertThat(value.getTextValue()).isEqualTo("google");
        assertThat(value.getConfiguration()).isNull();
        assertThat(value.getSecret()).isNull();
    }

    @Test
    void storesConcurrentExecutionPreferenceOnTheAlert() {
        Alert alert = new Alert(
                template(), "Concurrent", null, "0 */5 * * * *", true, true
        );

        assertThat(alert.isConcurrentExecutionAllowed()).isTrue();

        alert.changeConcurrentExecution(false);

        assertThat(alert.isConcurrentExecutionAllowed()).isFalse();
    }

    @Test
    void rejectsBindingWhenTheTemplateParameterDoesNotAllowIt() {
        AlertTemplateDefinition template = template();
        AlertTemplateParameterDefinition parameter = new AlertTemplateParameterDefinition(
                template, "endpoint", "endpoint.label", "endpoint.description", String.class.getName(),
                List.of("google", "cloudflare"), false, "google", 1, true
        );
        Alert alert = new Alert(template, "Internet", null, "0 */5 * * * *", true);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> valueWithDisallowedConfiguration(alert, parameter));
    }

    @Test
    void rejectsDirectValueOutsideOptionsWhenBindingIsDisabled() {
        AlertTemplateDefinition template = template();
        AlertTemplateParameterDefinition parameter = new AlertTemplateParameterDefinition(
                template, "endpoint", "endpoint.label", "endpoint.description", String.class.getName(),
                List.of("google", "cloudflare"), false, "google", 1, true
        );
        Alert alert = new Alert(template, "Internet", null, "0 */5 * * * *", true);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertParameterValue.text(alert, parameter, "other"));
    }

    @Test
    void rejectsDefaultOutsideOptionsWhenBindingIsDisabled() {
        AlertTemplateDefinition template = template();

        assertThatIllegalArgumentException().isThrownBy(
            () -> new AlertTemplateParameterDefinition(
                template, "endpoint", "endpoint.label", "endpoint.description",
                String.class.getName(), List.of("google", "cloudflare"),
                false, "other", 1, true
            )
        );
    }

    @Test
    void reservesErrorStatusForExceptionExecutions() {
        Alert alert = new Alert(template(), "Internet", null, "0 */5 * * * *", true);
        Instant startedAt = Instant.parse("2026-08-26T12:00:00Z");
        Instant finishedAt = startedAt.plusSeconds(1);
        UUID executionId = UUID.randomUUID();
        UUID workerInstanceId = UUID.randomUUID();
        AlertExecutionWorker worker = new AlertExecutionWorker(
                "standard-worker", "10.0.0.2", 9090, workerInstanceId
        );

        AlertState state = new AlertState(1L, null);
        state.replaceState("ok");

        assertThat(state.getState()).isEqualTo("ok");
        assertThat(AlertExecution.result(executionId, alert, null, SUCCESS, startedAt, startedAt, finishedAt, null).getStatus())
                .isEqualTo(SUCCESS);
        assertThat(AlertExecution.result(executionId, alert, null, WARN, startedAt, startedAt, finishedAt, null).getStatus())
                .isEqualTo(WARN);
        assertThat(AlertExecution.error(executionId, alert, null, startedAt, startedAt, finishedAt, "java.io.IOException", "offline", "stack").getStatus())
                .isEqualTo(ERROR);
        AlertExecution workerExecution = AlertExecution.result(
                executionId, alert, worker, SUCCESS, startedAt, startedAt, finishedAt, null
        );
        assertThat(workerExecution.getExecutionId()).isEqualTo(executionId);
        assertThat(workerExecution.getWorkerName()).isEqualTo("standard-worker");
        assertThat(workerExecution.getWorkerIpAddress()).isEqualTo("10.0.0.2");
        assertThat(workerExecution.getWorkerPort()).isEqualTo(9090);
        assertThat(workerExecution.getWorkerInstanceId()).isEqualTo(workerInstanceId);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertExecution.result(executionId, alert, null, ERROR, startedAt, startedAt, finishedAt, null));
    }

    private static void valueWithDisallowedConfiguration(Alert alert, AlertTemplateParameterDefinition parameter) {
        AlertParameterValue.configuration(alert, parameter, null);
    }

    private static AlertTemplateDefinition template() {
        AlertTemplateDefinition template = AlertTemplateDefinition.from(SampleAlertTemplate.class);
        assertThat(template.getId()).isNull();
        assertThat(template.getTemplateKey()).isEqualTo(TEMPLATE_KEY);
        return template;
    }

    @AlertTemplate(
        nameKey = "internet.name",
        descriptionKey = "internet.description",
        sourcePath = "sample/SampleAlertTemplate.java",
        capability = WorkerCapability.STANDARD
    )
    private static final class SampleAlertTemplate {
    }
}
