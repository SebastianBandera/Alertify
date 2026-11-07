package app.alertify.dashboard;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import app.alertify.pipes.model.PipeOutcome;

/** The last completed result and start of its status streak, independent of running executions. */
@Component
public class DashboardPipeExecutionQuery {
    static final String STATES_SQL = """
            select pipe.id as pipe_id, last.outcome, coalesce(streak.finished_at, last.finished_at) as state_changed_at
            from core.pipes pipe
            left join lateral (
                select execution.id, execution.outcome, execution.started_at, execution.finished_at
                from core.pipe_executions execution
                where execution.pipe_id = pipe.id and execution.status <> 'RUNNING' and execution.outcome is not null
                order by execution.started_at desc, execution.id desc
                limit 1
            ) last on true
            left join lateral (
                select execution.id, execution.started_at
                from core.pipe_executions execution
                where execution.pipe_id = pipe.id and execution.status <> 'RUNNING'
                  and execution.outcome <> last.outcome
                  and (execution.started_at, execution.id) < (last.started_at, last.id)
                order by execution.started_at desc, execution.id desc
                limit 1
            ) boundary on true
            left join lateral (
                select execution.finished_at
                from core.pipe_executions execution
                where execution.pipe_id = pipe.id and execution.status <> 'RUNNING'
                  and execution.outcome = last.outcome
                  and (execution.started_at, execution.id) <= (last.started_at, last.id)
                  and (boundary.id is null or (execution.started_at, execution.id) > (boundary.started_at, boundary.id))
                order by execution.started_at, execution.id
                limit 1
            ) streak on true
            where pipe.id in (:ids)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardPipeExecutionQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, State> states(List<Long> ids) {
        if (ids.isEmpty())
            return Map.of();

        Map<Long, State> states = new HashMap<>();
        jdbc.query(STATES_SQL, Map.of("ids", ids), row -> {
            String outcome = row.getString("outcome");
            OffsetDateTime changedAt = row.getObject("state_changed_at", OffsetDateTime.class);
            states.put(row.getLong("pipe_id"), new State(outcome == null ? null : PipeOutcome.valueOf(outcome),
                    changedAt == null ? null : changedAt.toInstant()));
        });
        return states;
    }

    public record State(PipeOutcome outcome, Instant changedAt) { }
}
