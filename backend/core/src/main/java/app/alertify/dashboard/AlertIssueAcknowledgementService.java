package app.alertify.dashboard;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.logging.ApplicationEventLogger;

/**
 * Per-user record of the alert issues each dashboard user has already seen:
 * one instant per user and alert, so marking an alert as seen covers every
 * issue up to that moment and a later issue is pending again on its own.
 * Stored apart from the alert itself so acknowledging never changes the
 * alert's edit version.
 */
@Service
public class AlertIssueAcknowledgementService {

    private final JdbcTemplate jdbcTemplate;
    private final AlertRepository alertRepository;
    private final ApplicationEventLogger eventLogger;

    public AlertIssueAcknowledgementService(JdbcTemplate jdbcTemplate, AlertRepository alertRepository, ApplicationEventLogger eventLogger) {
        this.jdbcTemplate = jdbcTemplate;
        this.alertRepository = alertRepository;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public List<AlertIssueAcknowledgementResponse> forUser(String userSubject) {
        return jdbcTemplate.query("""
                select alert_id, acknowledged_at
                from core.alert_issue_acknowledgements
                where user_subject = ?
                order by alert_id
                """,
                (resultSet, row) -> new AlertIssueAcknowledgementResponse(
                        resultSet.getLong("alert_id"),
                        resultSet.getObject("acknowledged_at", OffsetDateTime.class).toInstant()
                ),
                userSubject);
    }

    @Transactional
    public AlertIssueAcknowledgementResponse acknowledge(long alertId, String userSubject) {
        if (!alertRepository.existsById(alertId))
            throw new ResourceNotFoundException("Alert " + alertId + " was not found");

        Instant acknowledgedAt = jdbcTemplate.queryForObject("""
                insert into core.alert_issue_acknowledgements (alert_id, user_subject, acknowledged_at)
                values (?, ?, ?)
                on conflict (alert_id, user_subject) do update set acknowledged_at = excluded.acknowledged_at
                returning acknowledged_at
                """,
                (resultSet, row) -> resultSet.getObject("acknowledged_at", OffsetDateTime.class).toInstant(),
                alertId, userSubject, OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        eventLogger.successAfterCommit("ALERT_ISSUES_ACKNOWLEDGED", Map.of("alertId", alertId, "acknowledgedAt", acknowledgedAt.toString()));
        return new AlertIssueAcknowledgementResponse(alertId, acknowledgedAt);
    }
}
