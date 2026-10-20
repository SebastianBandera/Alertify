package app.alertify.alerts.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.ai.AiInvocationContextHolder;
import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.model.AlertExecutionClosureAudit;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.dashboard.DashboardEventPublisher;
import app.alertify.jpa.repository.AlertExecutionClosureAuditRepository;
import app.alertify.jpa.repository.AlertExecutionRepository;

@Service
public class AlertExecutionClosureService {
    private final AlertExecutionRepository executions;
    private final AlertExecutionClosureAuditRepository audits;
    private final DashboardEventPublisher events;

    public AlertExecutionClosureService(AlertExecutionRepository executions, AlertExecutionClosureAuditRepository audits, DashboardEventPublisher events) {
        this.executions = executions;
        this.audits = audits;
        this.events = events;
    }

    @Transactional
    public AlertExecutionResponse change(long id, boolean closed, String note, String subject, String actor) {
        var execution = executions.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Alert execution " + id + " was not found"));
        if (execution.getStatus() == app.alertify.alerts.execution.AlertExecutionStatus.SUCCESS)
            throw new ConflictException("Only WARN and ERROR executions may be closed or reopened");

        if (execution.isClosed() == closed)
            return AlertMapper.toExecution(execution);

        String normalized = note == null || note.isBlank() ? null : note.strip();
        if (normalized != null && normalized.length() > 2000)
            throw new IllegalArgumentException("Closure note must not exceed 2000 characters");

        Instant now = Instant.now();
        execution.changeClosure(closed, actor, normalized, now);
        audits.save(new AlertExecutionClosureAudit(execution.getExecutionId(), execution.getAlert().getId(), closed,
                subject, actor, normalized, now, AiInvocationContextHolder.currentProvenance()));
        events.alertChangedAfterCommit(execution.getAlert().getId());
        return AlertMapper.toExecution(execution);
    }

    @Transactional(readOnly = true)
    public List<ClosureAudit> audit(long id) {
        var execution = executions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Alert execution " + id + " was not found"));
        return audits.findByExecutionIdOrderByIdAsc(execution.getExecutionId()).stream()
                .map(audit -> new ClosureAudit(audit.isClosed(), audit.getActorName(), audit.getNote(), audit.getChangedAt(),
                        audit.isAiAssisted(), audit.getAiConversationId()))
                .toList();
    }

    public record ClosureAudit(boolean closed, String actor, String note, Instant at, boolean aiAssisted, Long aiConversationId) { }
}
