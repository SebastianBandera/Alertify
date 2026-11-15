package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class AiChatTurnBudgetTest {
    @Test
    void permitsExactly512RequestsIncludingReviewAndAssessmentWithoutResettingAt64() {
        AiChatTurnBudget budget = new AiChatTurnBudget();
        for (int index = 0; index < 512; index++) assertThat(budget.reserve("request-" + index)).isEmpty();
        assertThat(budget.remaining()).isZero();
        assertThat(budget.expired()).isFalse();
        assertThat(budget.reserve("request-513")).isEqualTo("AI_STEP_LIMIT");
    }

    @Test
    void originalDeadlineExpiresWithControlledClockAndDuplicateRequestsDoNotConsumeAgain() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        AiChatTurnBudget budget = new AiChatTurnBudget(clock);
        assertThat(budget.reserve("assessment")).isEmpty();
        assertThat(budget.reserve("assessment")).isEqualTo("AI_PROTOCOL_INVALID");
        assertThat(budget.remaining()).isEqualTo(511);
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(3600));
        assertThat(budget.reserve("review")).isEqualTo("AI_TURN_TIMEOUT");
        assertThat(budget.deadline()).isEqualTo(Instant.EPOCH.plusSeconds(3600));
    }
}
