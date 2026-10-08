package app.alertify.alerts.templates;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.scheduling.support.CronExpression;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Evaluates a reminder schedule independently from the Alert execution schedule.
 * Every cron occurrence since the last completed evaluation is coalesced into
 * one warning; the next evaluation without a new occurrence recovers to success.
 */
@AlertTemplate(
    nameKey = "alerts.template.scheduledReminder.name",
    descriptionKey = "alerts.template.scheduledReminder.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.reminder", color = "#8B5CF6"),
    persistentIssuesDefault = true,
    sourcePath = "app/alertify/alerts/templates/ScheduledReminderAlertTemplate.java"
)
public final class ScheduledReminderAlertTemplate implements AlertEvaluator {

    private static final int STATE_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AlertParameter(
        labelKey = "alerts.template.scheduledReminder.warningCron",
        descriptionKey = "alerts.template.scheduledReminder.warningCronDescription",
        allowedSources = AlertParameterSource.TEXT,
        order = 1
    )
    private final CronExpression warningCron;

    @AlertParameter(
        labelKey = "alerts.template.scheduledReminder.warningMessage",
        descriptionKey = "alerts.template.scheduledReminder.warningMessageDescription",
        multiline = true,
        allowedSources = AlertParameterSource.TEXT,
        order = 2
    )
    private final String warningMessage;

    private final Clock clock;

    public ScheduledReminderAlertTemplate(CronExpression warningCron, String warningMessage) {
        this(warningCron, warningMessage, Clock.systemDefaultZone());
    }

    ScheduledReminderAlertTemplate(CronExpression warningCron, String warningMessage, Clock clock) {
        this.warningCron = Objects.requireNonNull(warningCron, "warningCron must not be null");
        if (warningMessage == null || warningMessage.isBlank())
            throw new IllegalArgumentException("warningMessage must not be blank");

        this.warningMessage = warningMessage.trim();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) {
        Objects.requireNonNull(context, "context must not be null");
        Instant checkedAt = clock.instant();
        ZoneId zone = clock.getZone();
        Instant previousCheckpoint = checkpoint(context.getState());
        ZonedDateTime intervalStart = previousCheckpoint == null
                ? checkedAt.atZone(zone).toLocalDate().atStartOfDay(zone).minusNanos(1)
                : previousCheckpoint.atZone(zone);
        ZonedDateTime scheduled = warningCron.next(intervalStart);
        boolean warning = scheduled != null && !scheduled.toInstant().isAfter(checkedAt);
        Instant checkpoint = previousCheckpoint != null && previousCheckpoint.isAfter(checkedAt)
                ? previousCheckpoint
                : checkedAt;
        context.setState(state(checkpoint));

        Map<String, Object> statusMessage = new LinkedHashMap<>();
        if (warning) {
            statusMessage.put("message", warningMessage);
            statusMessage.put("scheduledAt", scheduled.toInstant().toString());
        }
        statusMessage.put("checkedAt", checkedAt.toString());
        ZonedDateTime nextScheduled = warningCron.next(checkedAt.atZone(zone));
        if (nextScheduled != null)
            statusMessage.put("nextScheduledAt", nextScheduled.toInstant().toString());

        return warning ? AlertResult.warn(statusMessage) : AlertResult.success(statusMessage);
    }

    private static Instant checkpoint(String state) {
        if (state == null || state.isBlank())
            return null;

        try {
            JsonNode document = JSON.readTree(state);
            JsonNode version = document.get("version");
            JsonNode checkedThrough = document.get("checkedThrough");
            if (version == null || version.asInt() != STATE_VERSION || checkedThrough == null || !checkedThrough.isString())
                return null;

            return Instant.parse(checkedThrough.stringValue());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String state(Instant checkpoint) {
        ObjectNode document = JSON.createObjectNode();
        document.put("version", STATE_VERSION);
        document.put("checkedThrough", checkpoint.toString());
        return document.toString();
    }
}
