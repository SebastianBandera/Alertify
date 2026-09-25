package app.alertify.alerts.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import app.alertify.worker.contract.WorkerCapability;

class AlertPersistentIssuesTest {

    private static final Instant ENABLED_AT = Instant.parse("2026-09-24T10:00:00Z");

    @Test
    void isOffForANewAlert() {
        assertThat(alert().getPersistentIssuesSince()).isNull();
    }

    @Test
    void turningItOnRecordsWhenSoOlderIssuesNeverCount() {
        Alert alert = alert();

        alert.changePersistentIssues(true, ENABLED_AT);

        assertThat(alert.getPersistentIssuesSince()).isEqualTo(ENABLED_AT);
    }

    @Test
    void savingItOnAgainKeepsTheOriginalStart() {
        Alert alert = alert();
        alert.changePersistentIssues(true, ENABLED_AT);

        alert.changePersistentIssues(true, ENABLED_AT.plusSeconds(3_600));

        assertThat(alert.getPersistentIssuesSince()).isEqualTo(ENABLED_AT);
    }

    @Test
    void turningItOffClearsItAndTurningItOnAgainStartsOver() {
        Alert alert = alert();
        alert.changePersistentIssues(true, ENABLED_AT);

        alert.changePersistentIssues(false, ENABLED_AT.plusSeconds(60));
        assertThat(alert.getPersistentIssuesSince()).isNull();

        alert.changePersistentIssues(true, ENABLED_AT.plusSeconds(120));
        assertThat(alert.getPersistentIssuesSince()).isEqualTo(ENABLED_AT.plusSeconds(120));
    }

    private static Alert alert() {
        AlertTemplateDefinition template = new AlertTemplateDefinition(
                "app.alertify.alerts.templates.InternetConnectionAlertTemplate",
                "name.key", "description.key", "source/path.java", WorkerCapability.STANDARD
        );
        return new Alert(template, "Alert", null, "-", true);
    }
}
