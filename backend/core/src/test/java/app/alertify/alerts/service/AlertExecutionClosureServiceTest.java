package app.alertify.alerts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertExecution;
import app.alertify.alerts.model.AlertExecutionClosureAudit;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.api.error.ConflictException;
import app.alertify.dashboard.DashboardEventPublisher;
import app.alertify.jpa.repository.AlertExecutionClosureAuditRepository;
import app.alertify.jpa.repository.AlertExecutionRepository;

class AlertExecutionClosureServiceTest {
    private final AlertExecutionRepository repository = mock(AlertExecutionRepository.class);
    private final AlertExecutionClosureAuditRepository audits = mock(AlertExecutionClosureAuditRepository.class);
    private final DashboardEventPublisher events = mock(DashboardEventPublisher.class);
    private final AlertExecutionClosureService service = new AlertExecutionClosureService(repository, audits, events);

    @Test
    void closingAndReopeningRetainExecutionAndPublishChanges() {
        var execution = execution(AlertExecutionStatus.WARN);
        when(repository.lockById(1L)).thenReturn(Optional.of(execution));
        assertEquals(true, service.change(1, true, " resolved ", "subject", "admin").closed());
        assertEquals("resolved", execution.getClosureNote());
        assertEquals(false, service.change(1, false, "recheck", "subject", "reviewer").closed());
        assertEquals("reviewer", execution.getClosureBy());
        ArgumentCaptor<AlertExecutionClosureAudit> saved = ArgumentCaptor.forClass(AlertExecutionClosureAudit.class);
        verify(audits, org.mockito.Mockito.times(2)).save(saved.capture());
        var closure = saved.getAllValues().get(0);
        var reopening = saved.getAllValues().get(1);
        assertEquals(execution.getExecutionId(), closure.getExecutionId());
        assertEquals(3L, closure.getAlertId());
        assertEquals(true, closure.isClosed());
        assertEquals("subject", closure.getActorSubject());
        assertEquals("admin", closure.getActorName());
        assertEquals("resolved", closure.getNote());
        assertEquals(false, reopening.isClosed());
        assertEquals("reviewer", reopening.getActorName());
        assertEquals(execution.getClosureAt(), reopening.getChangedAt());
        verify(events, org.mockito.Mockito.times(2)).alertChangedAfterCommit(3L);
    }

    @Test
    void repeatedRequestDoesNotAppendAuditAndSuccessCannotClose() {
        var execution = execution(AlertExecutionStatus.WARN);
        execution.changeClosure(true, "admin", null, Instant.now());
        when(repository.lockById(1L)).thenReturn(Optional.of(execution));
        service.change(1, true, null, "subject", "admin");
        verify(audits, never()).save(any());
        var success = execution(AlertExecutionStatus.SUCCESS);
        when(repository.lockById(1L)).thenReturn(Optional.of(success));
        assertThrows(ConflictException.class, () -> service.change(1, true, null, "subject", "admin"));
        verify(audits, never()).save(any());
    }

    @Test
    void returnsTheOrderedAuditWithoutTheInternalSubject() {
        var execution = execution(AlertExecutionStatus.WARN);
        when(repository.findById(1L)).thenReturn(Optional.of(execution));
        Instant at = Instant.parse("2026-10-03T12:00:00Z");
        when(audits.findByExecutionIdOrderByIdAsc(execution.getExecutionId())).thenReturn(List.of(
                new AlertExecutionClosureAudit(execution.getExecutionId(), 3L, true, "internal-subject", "admin", "resolved", at),
                new AlertExecutionClosureAudit(execution.getExecutionId(), 3L, false, "internal-subject", "reviewer", null, at.plusSeconds(1))
        ));

        assertEquals(List.of(new AlertExecutionClosureService.ClosureAudit(true, "admin", "resolved", at),
                new AlertExecutionClosureService.ClosureAudit(false, "reviewer", null, at.plusSeconds(1))), service.audit(1L));
    }

    private static AlertExecution execution(AlertExecutionStatus status) {
        Alert alert = mock(Alert.class);
        when(alert.getId()).thenReturn(3L);
        when(alert.getTemplate()).thenReturn(mock(AlertTemplateDefinition.class));
        Instant now = Instant.now();
        return AlertExecution.result(UUID.randomUUID(), alert, null, status, now, now, now, null);
    }
}
