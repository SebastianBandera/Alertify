package app.alertify.dashboard;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Component;

import app.alertify.alerts.execution.AlertExecutionStatus;

/**
 * Batch reporting queries over {@code core.alert_executions} for the dashboard.
 * JDBC keeps these aggregates separate from the execution entity lifecycle,
 * like the worker activity history does.
 *
 * <p>Every figure is a {@code LATERAL ... LIMIT 1} probe on the
 * {@code (alert_id, status, started_at desc)} index, so the cost per alert
 * stays flat no matter how many executions it accumulated.
 */
@Component
public class DashboardExecutionQuery {

    private final JdbcTemplate jdbcTemplate;

    public DashboardExecutionQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Row id of the most recent execution of each alert, for the alerts that ran at least once. */
    public Map<Long, Long> latestExecutionIds(Collection<Long> alertIds) {
        if (alertIds.isEmpty())
            return Collections.emptyMap();

        Map<Long, Long> result = new HashMap<>();
        jdbcTemplate.query("""
                select alerts.alert_id, latest.id
                from (values %s) as alerts(alert_id)
                cross join lateral (
                    select execution.id
                    from core.alert_executions execution
                    where execution.alert_id = alerts.alert_id
                    order by execution.started_at desc, execution.id desc
                    limit 1
                ) latest
                """.formatted(valueRows(alertIds.size())),
                resultSet -> {
                    result.put(resultSet.getLong("alert_id"), resultSet.getLong("id"));
                },
                alertIds.toArray());
        return result;
    }

    /**
     * Row id of the most recent WARN or ERROR execution of each alert that
     * precedes its latest execution and started since {@code since}. Takes the
     * latest execution id per alert so the probe never returns the latest
     * execution itself.
     */
    public Map<Long, Long> previousIssueExecutionIds(Map<Long, Long> latestExecutionIds, Instant since) {
        if (latestExecutionIds.isEmpty())
            return Collections.emptyMap();

        List<Object> parameters = new ArrayList<>();
        latestExecutionIds.forEach((alertId, latestId) -> {
            parameters.add(alertId);
            parameters.add(latestId);
        });
        parameters.add(timestamp(since));
        Map<Long, Long> result = new HashMap<>();
        jdbcTemplate.query("""
                select alerts.alert_id, previous.id
                from (values %s) as alerts(alert_id, latest_id)
                cross join lateral (
                    select execution.id
                    from core.alert_executions execution
                    where execution.alert_id = alerts.alert_id
                      and execution.status in ('WARN', 'ERROR')
                      and execution.id <> alerts.latest_id
                      and execution.started_at >= ?
                    order by execution.started_at desc, execution.id desc
                    limit 1
                ) previous
                """.formatted(valueRows(latestExecutionIds.size(), 2)),
                resultSet -> {
                    result.put(resultSet.getLong("alert_id"), resultSet.getLong("id"));
                },
                parameters.toArray());
        return result;
    }

    /**
     * Look-back summary of each alert that ran at least once. The window covers
     * executions started since {@code since}, always including the latest one
     * so an alert that has been idle for longer still gets a summary.
     */
    public Map<Long, DashboardHistorySummaryResponse> historySummaries(Collection<Long> alertIds, Instant since) {
        if (alertIds.isEmpty())
            return Collections.emptyMap();

        List<Object> parameters = new ArrayList<>(alertIds);
        parameters.add(timestamp(since));
        Map<Long, DashboardHistorySummaryResponse> result = new HashMap<>();
        jdbcTemplate.query("""
                select distinct on (alerts.alert_id)
                       alerts.alert_id, candidate.status as worst_status, candidate.last_at as worst_last_at,
                       coalesce(streak.since, last.finished_at) as since
                from (values %s) as alerts(alert_id)
                cross join lateral (
                    select execution.status, execution.started_at, execution.finished_at
                    from core.alert_executions execution
                    where execution.alert_id = alerts.alert_id
                    order by execution.started_at desc, execution.id desc
                    limit 1
                ) last
                left join lateral (
                    select max(other.started_at) as started_at
                    from (values ('SUCCESS'), ('WARN'), ('ERROR')) as kinds(status)
                    cross join lateral (
                        select execution.started_at
                        from core.alert_executions execution
                        where execution.alert_id = alerts.alert_id and execution.status = kinds.status
                        order by execution.started_at desc, execution.id desc
                        limit 1
                    ) other
                    where kinds.status <> last.status
                ) boundary on true
                left join lateral (
                    select execution.finished_at as since
                    from core.alert_executions execution
                    where execution.alert_id = alerts.alert_id and execution.status = last.status
                      and (boundary.started_at is null or execution.started_at > boundary.started_at)
                    order by execution.started_at asc, execution.id asc
                    limit 1
                ) streak on true
                cross join lateral (
                    select kinds.status, kinds.severity,
                           coalesce(
                               (select execution.finished_at
                                from core.alert_executions execution
                                where execution.alert_id = alerts.alert_id and execution.status = kinds.status
                                  and execution.started_at >= ?
                                order by execution.started_at desc, execution.id desc
                                limit 1),
                               case when kinds.status = last.status then last.finished_at end
                           ) as last_at
                    from (values ('ERROR', 2), ('WARN', 1), ('SUCCESS', 0)) as kinds(status, severity)
                ) candidate
                where candidate.last_at is not null
                order by alerts.alert_id, candidate.severity desc
                """.formatted(valueRows(alertIds.size())),
                resultSet -> {
                    result.put(resultSet.getLong("alert_id"), new DashboardHistorySummaryResponse(
                            AlertExecutionStatus.valueOf(resultSet.getString("worst_status")),
                            instant(resultSet.getObject("worst_last_at", OffsetDateTime.class)),
                            instant(resultSet.getObject("since", OffsetDateTime.class))
                    ));
                },
                parameters.toArray());
        return result;
    }

    /**
     * When each alert last finished a WARN and an ERROR since its persistent
     * issues were turned on, with no look-back window: a past issue stays
     * relevant until each user marks it as seen. Keyed by alert id; alerts
     * with neither issue are left out.
     */
    public Map<Long, DashboardIssueTimesResponse> lastIssueTimes(Map<Long, Instant> persistentSince) {
        if (persistentSince.isEmpty())
            return Collections.emptyMap();

        List<Object> parameters = new ArrayList<>();
        persistentSince.forEach((alertId, since) -> {
            parameters.add(alertId);
            parameters.add(timestamp(since));
        });
        String rows = String.join(", ", Collections.nCopies(persistentSince.size(), "(?, ?::timestamptz)"));
        Map<Long, DashboardIssueTimesResponse> result = new HashMap<>();
        jdbcTemplate.query("""
                select alerts.alert_id, last_warn.finished_at as last_warn_at, last_error.finished_at as last_error_at
                from (values %s) as alerts(alert_id, since)
                left join lateral (
                    select execution.finished_at
                    from core.alert_executions execution
                    where execution.alert_id = alerts.alert_id and execution.status = 'WARN'
                      and execution.started_at >= alerts.since
                    order by execution.started_at desc, execution.id desc
                    limit 1
                ) last_warn on true
                left join lateral (
                    select execution.finished_at
                    from core.alert_executions execution
                    where execution.alert_id = alerts.alert_id and execution.status = 'ERROR'
                      and execution.started_at >= alerts.since
                    order by execution.started_at desc, execution.id desc
                    limit 1
                ) last_error on true
                where last_warn.finished_at is not null or last_error.finished_at is not null
                """.formatted(rows),
                resultSet -> {
                    result.put(resultSet.getLong("alert_id"), new DashboardIssueTimesResponse(
                            instant(resultSet.getObject("last_warn_at", OffsetDateTime.class)),
                            instant(resultSet.getObject("last_error_at", OffsetDateTime.class))
                    ));
                },
                parameters.toArray());
        return result;
    }

    private static String valueRows(int count) {
        return valueRows(count, 1);
    }

    private static String valueRows(int count, int columns) {
        String row = "(" + String.join(", ", Collections.nCopies(columns, "?")) + ")";
        return String.join(", ", Collections.nCopies(count, row));
    }

    private static SqlParameterValue timestamp(Instant value) {
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
