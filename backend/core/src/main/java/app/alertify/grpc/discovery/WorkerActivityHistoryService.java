package app.alertify.grpc.discovery;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Service;

import app.alertify.grpc.api.WorkerActivityHistoryResponse;
import app.alertify.grpc.api.WorkerActivitySeriesResponse;

/**
 * Builds the fixed 24-hour activity graph from completed execution rows. JDBC
 * keeps this reporting query separate from the execution entity lifecycle.
 */
@Service
public class WorkerActivityHistoryService {

    private static final int MINUTE_COUNT = 24 * 60;
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final JdbcTemplate jdbcTemplate;

    public WorkerActivityHistoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public WorkerActivityHistoryResponse history() {
        Instant to = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant from = to.minus(MINUTE_COUNT, ChronoUnit.MINUTES);
        Map<SeriesKey, SeriesData> series = new HashMap<>();
        for (ExecutionInterval interval : completedIntervals(from, to))
            add(series, interval, from, to);

        List<WorkerActivitySeriesResponse> response = series.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<SeriesKey, SeriesData> entry) -> entry.getValue().workerName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(entry -> entry.getKey().workerInstanceId.toString())
                        .thenComparing(entry -> entry.getKey().kind))
                .map(entry -> entry.getValue().response(entry.getKey(), from))
                .toList();
        return new WorkerActivityHistoryResponse(from, to, response);
    }

    private List<ExecutionInterval> completedIntervals(Instant from, Instant to) {
        return jdbcTemplate.query("""
                select worker_instance_id, worker_name, worker_ip_address, worker_port, work_started_at, finished_at, 'ALERT' as kind
                from core.alert_executions
                where worker_instance_id is not null
                  and work_started_at < ? and finished_at > ?
                union all
                select worker_instance_id, worker_name, worker_ip_address, worker_port, work_started_at, finished_at, 'PROCEDURE' as kind
                from core.procedure_executions
                where worker_instance_id is not null
                  and work_started_at is not null and finished_at is not null
                  and work_started_at < ? and finished_at > ?
                """, (resultSet, rowNumber) -> new ExecutionInterval(
                resultSet.getObject("worker_instance_id", UUID.class),
                resultSet.getString("worker_name"),
                address(resultSet.getString("worker_ip_address"), resultSet.getObject("worker_port", Integer.class)),
                resultSet.getString("kind"),
                instant(resultSet.getObject("work_started_at", OffsetDateTime.class)),
                instant(resultSet.getObject("finished_at", OffsetDateTime.class))
        ), timestamp(to), timestamp(from), timestamp(to), timestamp(from));
    }

    private static SqlParameterValue timestamp(Instant value) {
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    private void add(Map<SeriesKey, SeriesData> series, ExecutionInterval interval, Instant from, Instant to) {
        Instant start = interval.workStartedAt.isBefore(from) ? from : interval.workStartedAt;
        Instant end = interval.finishedAt.isAfter(to) ? to : interval.finishedAt;
        if (!start.isBefore(end))
            return;

        SeriesKey key = new SeriesKey(interval.workerInstanceId, interval.kind);
        SeriesData data = series.computeIfAbsent(key, ignored -> new SeriesData(interval.workerName, interval.address));
        series.computeIfAbsent(new SeriesKey(interval.workerInstanceId, "ALERT"), ignored -> new SeriesData(interval.workerName, interval.address));
        series.computeIfAbsent(new SeriesKey(interval.workerInstanceId, "PROCEDURE"), ignored -> new SeriesData(interval.workerName, interval.address));
        int firstMinute = (int) Duration.between(from, start).toMinutes();
        int lastMinute = (int) Duration.between(from, end.minusNanos(1)).toMinutes();
        for (int minute = firstMinute; minute <= lastMinute; minute++)
            data.intervals.get(minute).add(new Interval(start, end));
    }

    private static String address(String ipAddress, Integer port) {
        if (ipAddress == null || ipAddress.isBlank())
            return null;

        return port == null ? ipAddress : ipAddress + ':' + port;
    }

    private static int peak(List<Interval> intervals, Instant minuteStart) {
        Instant minuteEnd = minuteStart.plus(MINUTE);
        int active = 0;
        Map<Instant, Integer> changes = new TreeMap<>();
        for (Interval interval : intervals) {
            if (!interval.start.isAfter(minuteStart) && interval.end.isAfter(minuteStart))
                active++;
            if (interval.start.isAfter(minuteStart) && interval.start.isBefore(minuteEnd))
                changes.merge(interval.start, 1, Integer::sum);
            if (interval.end.isAfter(minuteStart) && interval.end.isBefore(minuteEnd))
                changes.merge(interval.end, -1, Integer::sum);
        }

        int peak = active;
        for (int change : changes.values()) {
            active += change;
            peak = Math.max(peak, active);
        }
        return peak;
    }

    private record ExecutionInterval(UUID workerInstanceId, String workerName, String address, String kind, Instant workStartedAt, Instant finishedAt) {
    }

    private record Interval(Instant start, Instant end) {
    }

    private record SeriesKey(UUID workerInstanceId, String kind) {
    }

    private static final class SeriesData {

        private final String workerName;
        private final String address;
        private final List<List<Interval>> intervals = new ArrayList<>(MINUTE_COUNT);

        private SeriesData(String workerName, String address) {
            this.workerName = workerName;
            this.address = address;
            for (int minute = 0; minute < MINUTE_COUNT; minute++)
                intervals.add(new ArrayList<>());
        }

        private WorkerActivitySeriesResponse response(SeriesKey key, Instant from) {
            List<Integer> values = new ArrayList<>(MINUTE_COUNT);
            for (int minute = 0; minute < MINUTE_COUNT; minute++)
                values.add(peak(intervals.get(minute), from.plus(minute, ChronoUnit.MINUTES)));

            return new WorkerActivitySeriesResponse(key.workerInstanceId, workerName, address, key.kind, values);
        }
    }
}
