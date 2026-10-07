package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;

class ScheduledReminderAlertTemplateTest {

    private static final ZoneId MONTEVIDEO = ZoneId.of("America/Montevideo");

    @Test
    void succeedsBeforeTodaysFirstOccurrence() {
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = template("0 0 9 * * *", "Preparar el parte", "2026-10-06T11:00:00Z").evaluate(context);

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals("2026-10-06T12:00:00Z", result.statusMessage().get("nextScheduledAt"));
        assertTrue(context.getState().contains("2026-10-06T11:00:00Z"));
    }

    @Test
    void warnsAfterTodaysOccurrenceOnTheFirstExecution() {
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = template("0 0 9 * * *", "Preparar el parte", "2026-10-06T13:00:00Z").evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals("Preparar el parte", result.statusMessage().get("message"));
        assertEquals("2026-10-06T12:00:00Z", result.statusMessage().get("scheduledAt"));
    }

    @Test
    void recoversWhenNoNewOccurrenceFollowedTheWarning() {
        AlertExecutionContext context = new AlertExecutionContext();
        template("0 0 9 * * *", "Preparar el parte", "2026-10-06T13:00:00Z").evaluate(context);

        AlertResult result = template("0 0 9 * * *", "Preparar el parte", "2026-10-06T14:00:00Z").evaluate(context);

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
    }

    @Test
    void coalescesSeveralMissedOccurrencesIntoOneWarning() {
        AlertExecutionContext context = new AlertExecutionContext("{\"version\":1,\"checkedThrough\":\"2026-10-01T12:30:00Z\"}");

        AlertResult warning = template("0 0 9 * * *", "Preparar el parte", "2026-10-06T13:00:00Z").evaluate(context);
        AlertResult recovery = template("0 0 9 * * *", "Preparar el parte", "2026-10-06T14:00:00Z").evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, warning.status());
        assertEquals("2026-10-02T12:00:00Z", warning.statusMessage().get("scheduledAt"));
        assertEquals(AlertExecutionStatus.SUCCESS, recovery.status());
    }

    @Test
    void invalidStateFallsBackToTheStartOfTheCurrentDay() {
        AlertExecutionContext context = new AlertExecutionContext("not-json");

        AlertResult result = template("0 0 9 * * *", "Preparar el parte", "2026-10-06T13:00:00Z").evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals("2026-10-06T12:00:00Z", result.statusMessage().get("scheduledAt"));
    }

    @Test
    void keepsTheFutureCheckpointWhenTheClockMovesBackwards() {
        AlertExecutionContext context = new AlertExecutionContext("{\"version\":1,\"checkedThrough\":\"2026-10-06T15:00:00Z\"}");

        AlertResult result = template("0 0 9 * * *", "Preparar el parte", "2026-10-06T14:00:00Z").evaluate(context);

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertTrue(context.getState().contains("2026-10-06T15:00:00Z"));
    }

    @Test
    void rejectsBlankMessages() {
        CronExpression cron = CronExpression.parse("0 0 9 * * *");

        assertThrows(IllegalArgumentException.class, () -> new ScheduledReminderAlertTemplate(cron, " "));
        assertThrows(IllegalArgumentException.class, () -> new ScheduledReminderAlertTemplate(cron, null));
    }

    private static ScheduledReminderAlertTemplate template(String expression, String message, String instant) {
        return new ScheduledReminderAlertTemplate(
                CronExpression.parse(expression), message,
                Clock.fixed(Instant.parse(instant), MONTEVIDEO)
        );
    }
}
