package app.alertify.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.realtime.AdminEventPublisher;

@ExtendWith(MockitoExtension.class)
class DashboardEventPublisherTest {

    @Mock private AdminEventPublisher eventPublisher;
    @Mock private DashboardCardService cardService;

    @Test
    void runningSinceIsTheEarliestActiveExecution() {
        AlertExecutionRunningRegistry registry = new AlertExecutionRunningRegistry();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant firstStart = Instant.parse("2026-09-19T10:00:00Z");

        registry.started(1L, second, firstStart.plusSeconds(30));
        registry.started(1L, first, firstStart);
        assertThat(registry.runningSince(1L)).contains(firstStart);

        registry.finished(1L, first);
        assertThat(registry.runningSince(1L)).contains(firstStart.plusSeconds(30));

        registry.finished(1L, second);
        assertThat(registry.runningSince(1L)).isEmpty();
        assertThat(registry.runningSince(2L)).isEmpty();
    }

    @Test
    void executionFinishedClearsTheMarkerAndPublishesTheTile() {
        AlertExecutionRunningRegistry registry = new AlertExecutionRunningRegistry();
        DashboardCardResponse card = new DashboardCardResponse(null, null, null, null, null);
        UUID executionId = UUID.randomUUID();
        when(eventPublisher.hasAuthenticatedSessions()).thenReturn(true);
        when(cardService.card(1L)).thenReturn(Optional.of(card));

        try (DashboardEventPublisher publisher = new DashboardEventPublisher(eventPublisher, cardService, registry)) {
            publisher.executionStarted(1L, executionId, Instant.now());
            publisher.executionFinished(1L, executionId);
        }

        assertThat(registry.runningSince(1L)).isEmpty();
        verify(eventPublisher, timeout(2_000).times(2)).publish(DashboardEventPublisher.ALERT_EVENT, card);
    }

    @Test
    void nothingIsPublishedWithoutAuthenticatedSessions() {
        when(eventPublisher.hasAuthenticatedSessions()).thenReturn(false);

        try (DashboardEventPublisher publisher = new DashboardEventPublisher(eventPublisher, cardService, new AlertExecutionRunningRegistry())) {
            publisher.executionStarted(1L, UUID.randomUUID(), Instant.now());
            publisher.alertRemovedAfterCommit(1L);
        }

        verify(cardService, never()).card(1L);
        verify(eventPublisher, never()).publish(anyString(), any());
    }

    @Test
    void alertRemovalIsPublishedImmediatelyOutsideATransaction() {
        when(eventPublisher.hasAuthenticatedSessions()).thenReturn(true);

        try (DashboardEventPublisher publisher = new DashboardEventPublisher(eventPublisher, cardService, new AlertExecutionRunningRegistry())) {
            publisher.alertRemovedAfterCommit(4L);
        }

        verify(eventPublisher).publish(DashboardEventPublisher.ALERT_REMOVED_EVENT, new DashboardEventPublisher.AlertRemovedPayload(4L));
    }
}
