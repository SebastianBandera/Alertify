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
                select distinct on (alert_id) alert_id, id
                from core.alert_executions
                where alert_id in (%s)
                order by alert_id, started_at desc, id desc
                """.formatted(placeholders(alertIds.size())),
                resultSet -> {
                    result.put(resultSet.getLong("alert_id"), resultSet.getLong("id"));
                },
                alertIds.toArray());
        return result;
    }

    /**
     * Look-back summary of each alert that ran at least once. The window covers
     * executions finished since {@code since}, always including the latest one
     * so an alert that has been idle for longer still gets a summary.
     */
    public Map<Long, DashboardHistorySummaryResponse> historySummaries(Collection<Long> alertIds, Instant since) {
        if (alertIds.isEmpty())
            return Collections.emptyMap();

        List<Object> parameters = new ArrayList<>(alertIds);
        parameters.add(timestamp(since));
        Map<Long, DashboardHistorySummaryResponse> result = new HashMap<>();
        jdbcTemplate.query("""
                with last as (
                    select distinct on (alert_id) alert_id, status, finished_at
                    from core.alert_executions
                    where alert_id in (%s)
                    order by alert_id, started_at desc, id desc
                ), boundary as (
                    select execution.alert_id, max(execution.finished_at) as last_other_at
                    from core.alert_executions execution
                    join last on last.alert_id = execution.alert_id
                    where execution.status <> last.status
                    group by execution.alert_id
                ), streak as (
                    select execution.alert_id, min(execution.finished_at) as since
                    from core.alert_executions execution
                    join last on last.alert_id = execution.alert_id
                    left join boundary on boundary.alert_id = execution.alert_id
                    where execution.status = last.status
                      and (boundary.last_other_at is null or execution.finished_at > boundary.last_other_at)
                    group by execution.alert_id
                ), ranked as (
                    select execution.alert_id, execution.status, max(execution.finished_at) as last_at,
                           case execution.status when 'ERROR' then 2 when 'WARN' then 1 else 0 end as severity
                    from core.alert_executions execution
                    join last on last.alert_id = execution.alert_id
                    where execution.finished_at >= ? or execution.finished_at >= last.finished_at
                    group by execution.alert_id, execution.status
                ), worst as (
                    select distinct on (alert_id) alert_id, status, last_at
                    from ranked
                    order by alert_id, severity desc
                )
                select worst.alert_id, worst.status, worst.last_at, streak.since
                from worst
                join streak on streak.alert_id = worst.alert_id
                """.formatted(placeholders(alertIds.size())),
                resultSet -> {
                    result.put(resultSet.getLong("alert_id"), new DashboardHistorySummaryResponse(
                            AlertExecutionStatus.valueOf(resultSet.getString("status")),
                            instant(resultSet.getObject("last_at", OffsetDateTime.class)),
                            instant(resultSet.getObject("since", OffsetDateTime.class))
                    ));
                },
                parameters.toArray());
        return result;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static SqlParameterValue timestamp(Instant value) {
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
