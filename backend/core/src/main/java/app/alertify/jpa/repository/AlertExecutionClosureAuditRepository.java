package app.alertify.jpa.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import app.alertify.alerts.model.AlertExecutionClosureAudit;

/** Deliberately exposes creation and ordered history without deletion operations. */
public interface AlertExecutionClosureAuditRepository extends Repository<AlertExecutionClosureAudit, Long> {

    AlertExecutionClosureAudit save(AlertExecutionClosureAudit audit);

    List<AlertExecutionClosureAudit> findByExecutionIdOrderByIdAsc(UUID executionId);
}
