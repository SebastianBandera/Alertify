package app.alertify.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.PipeExecutionRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.pipes.model.Pipe;
import app.alertify.pipes.model.PipeExecution;
import app.alertify.pipes.model.PipeExecutionStatus;
import app.alertify.pipes.model.PipeExecutionTrigger;
import app.alertify.pipes.model.PipeOutcome;

class DashboardPipeCardServiceTest {
    @ParameterizedTest
    @EnumSource(PipeOutcome.class)
    void keepsCompletedStateSeparateFromRunningProgressForAdminsAndViewers(PipeOutcome outcome) {
        Instant since = Instant.parse("2026-11-05T10:00:00Z");
        DashboardPipeCardResponse card = runningCard(new DashboardPipeExecutionQuery.State(outcome, since));

        assertThat(card.execution().status()).isEqualTo(PipeExecutionStatus.RUNNING);
        assertThat(card.execution().outcome()).isNull();
        assertThat(card.lastOutcome()).isEqualTo(outcome);
        assertThat(card.stateChangedAt()).isEqualTo(since);
        assertThat(card.forViewer().lastOutcome()).isEqualTo(outcome);
        assertThat(card.forViewer().stateChangedAt()).isEqualTo(since);
        assertThat(card.forViewer().execution().executionId()).isEqualTo(card.execution().executionId());
    }

    @Test
    void keepsFirstExecutionNeutralUntilItFinishes() {
        DashboardPipeCardResponse card = runningCard(new DashboardPipeExecutionQuery.State(null, null));

        assertThat(card.execution().status()).isEqualTo(PipeExecutionStatus.RUNNING);
        assertThat(card.lastOutcome()).isNull();
        assertThat(card.stateChangedAt()).isNull();
    }

    private static DashboardPipeCardResponse runningCard(DashboardPipeExecutionQuery.State state) {
        var pipes = mock(PipeRepository.class);
        var executions = mock(PipeExecutionRepository.class);
        var alerts = mock(AlertExecutionRepository.class);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var query = mock(DashboardPipeExecutionQuery.class);
        Pipe pipe = new Pipe("Test Pipe", null, true, false);
        ReflectionTestUtils.setField(pipe, "id", 7L);
        UUID executionId = UUID.randomUUID();
        PipeExecution execution = new PipeExecution(executionId, pipe, PipeExecutionTrigger.MANUAL, executionId, null, 0, "tester");
        ReflectionTestUtils.setField(execution, "id", 11L);
        when(pipes.findById(7L)).thenReturn(Optional.of(pipe));
        when(query.states(List.of(7L))).thenReturn(Map.of(7L, state));
        when(jdbc.queryForList(anyString(), eq(Map.of("ids", List.of(7L))), eq(Long.class))).thenReturn(List.of(11L));
        when(executions.findAllById(List.of(11L))).thenReturn(List.of(execution));

        return new DashboardPipeCardService(pipes, executions, alerts, jdbc, query).card(7L).orElseThrow();
    }
}
