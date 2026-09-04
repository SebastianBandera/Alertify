package app.alertify.jpa.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.model.AlertExecution;

public interface AlertExecutionRepository extends JpaRepository<AlertExecution, Long> {

    long countByAlert_Id(Long alertId);

    /**
     * Bulk delete, because one alert can accumulate thousands of executions and
     * loading them as entities just to remove them would be wasteful.
     */
    @Modifying
    @Query("delete from AlertExecution execution where execution.alert.id = :alertId")
    int deleteByAlertId(Long alertId);

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
