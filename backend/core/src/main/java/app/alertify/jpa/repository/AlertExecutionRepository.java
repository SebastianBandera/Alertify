package app.alertify.jpa.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.model.AlertExecution;

public interface AlertExecutionRepository extends JpaRepository<AlertExecution, Long> {

    boolean existsByAlert_Id(Long alertId);

    @Override
    @EntityGraph(attributePaths = "alert")
    Page<AlertExecution> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "alert")
    Page<AlertExecution> findAllByAlert_Id(Long alertId, Pageable pageable);

    @EntityGraph(attributePaths = "alert")
    Page<AlertExecution> findAllByStatus(AlertExecutionStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "alert")
    Page<AlertExecution> findAllByAlert_IdAndStatus(Long alertId, AlertExecutionStatus status, Pageable pageable);
}
