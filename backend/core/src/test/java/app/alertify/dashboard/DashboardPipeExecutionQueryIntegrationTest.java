package app.alertify.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import app.alertify.pipes.model.PipeOutcome;

/** Requires an empty disposable database; refuses a database already containing the application schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "ALERTIFY_DASHBOARD_PIPE_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+")
class DashboardPipeExecutionQueryIntegrationTest {
    private JdbcTemplate jdbc;
    private DashboardPipeExecutionQuery query;

    @BeforeAll
    void initialize() {
        var dataSource = new DriverManagerDataSource(System.getenv("ALERTIFY_DASHBOARD_PIPE_TEST_DATABASE_URL"), "postgres", "");
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select to_regnamespace('core')::text", String.class)).isNull();
        jdbc.execute("create schema core");
        jdbc.execute("create table core.pipes (id bigint primary key)");
        jdbc.execute("create table core.pipe_executions (id bigint primary key, pipe_id bigint, status text, outcome text, started_at timestamptz, finished_at timestamptz)");
        query = new DashboardPipeExecutionQuery(new NamedParameterJdbcTemplate(dataSource));
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.execute("truncate core.pipe_executions, core.pipes");
        jdbc.execute("insert into core.pipes values (1), (2), (3)");
    }

    @Test
    void ignoresRunningExecutionsAndKeepsNeverCompletedPipesNeutral() {
        execution(1, 1, "WARN", 10);
        execution(2, 1, null, 20);
        execution(3, 2, null, 30);

        var states = query.states(List.of(1L, 2L, 3L));
        assertThat(states.get(1L)).isEqualTo(new DashboardPipeExecutionQuery.State(PipeOutcome.WARN, finished(10)));
        assertThat(states.get(2L)).isEqualTo(new DashboardPipeExecutionQuery.State(null, null));
        assertThat(states.get(3L)).isEqualTo(new DashboardPipeExecutionQuery.State(null, null));
    }

    @Test
    void repeatedResultsKeepTheOriginalStateChangeTime() {
        execution(1, 1, "ERROR", 10);
        execution(2, 1, "WARN", 20);
        execution(3, 1, "WARN", 30);
        execution(4, 1, null, 40);

        assertThat(query.states(List.of(1L)).get(1L))
                .isEqualTo(new DashboardPipeExecutionQuery.State(PipeOutcome.WARN, finished(20)));
    }

    @Test
    void changesTheTimestampWhenTheOutcomeChanges() {
        execution(1, 1, "WARN", 10);
        execution(2, 1, "SUCCESS", 20);
        execution(3, 1, "ERROR", 30);

        assertThat(query.states(List.of(1L)).get(1L))
                .isEqualTo(new DashboardPipeExecutionQuery.State(PipeOutcome.ERROR, finished(30)));
    }

    @Test
    void breaksEqualStartTimesByExecutionId() {
        execution(1, 1, "ERROR", 10);
        execution(2, 1, "WARN", 10);
        execution(3, 1, "WARN", 20);

        assertThat(query.states(List.of(1L)).get(1L))
                .isEqualTo(new DashboardPipeExecutionQuery.State(PipeOutcome.WARN, finished(10)));
    }

    @Test
    void returnsAnEmptyMapForAnEmptyPage() {
        assertThat(query.states(List.of())).isEmpty();
    }

    private void execution(long id, long pipeId, String outcome, int startedSecond) {
        jdbc.update("insert into core.pipe_executions values (?, ?, ?, ?, ?, ?)", id, pipeId,
                outcome == null ? "RUNNING" : "COMPLETED", outcome,
                java.sql.Timestamp.from(Instant.parse("2026-11-05T10:00:00Z").plusSeconds(startedSecond)),
                outcome == null ? null : java.sql.Timestamp.from(finished(startedSecond)));
    }

    private static Instant finished(int startedSecond) {
        return Instant.parse("2026-11-05T10:00:00Z").plusSeconds(startedSecond + 1);
    }
}
