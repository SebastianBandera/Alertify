package app.alertify.jpa.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
    @EntityGraph(attributePaths = { "alert", "alert.template" })
    Page<AlertExecution> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = { "alert", "alert.template" })
    List<AlertExecution> findAllById(Iterable<Long> ids);

    @EntityGraph(attributePaths = { "alert", "alert.template" })
    Page<AlertExecution> findAllByAlert_Id(Long alertId, Pageable pageable);

    @EntityGraph(attributePaths = { "alert", "alert.template" })
    Page<AlertExecution> findAllByStatus(AlertExecutionStatus status, Pageable pageable);

    @EntityGraph(attributePaths = { "alert", "alert.template" })
    Page<AlertExecution> findAllByAlert_IdAndStatus(Long alertId, AlertExecutionStatus status, Pageable pageable);

    @EntityGraph(attributePaths = { "alert", "alert.template" })
    Page<AlertExecution> findAllByExecutionId(UUID executionId, Pageable pageable);

    @EntityGraph(attributePaths = { "alert", "alert.template" })
    @Query("""
            select execution from AlertExecution execution
            where (:alertId is null or execution.alert.id = :alertId)
              and (:templateId is null or execution.alert.template.id = :templateId)
              and (:status is null or execution.status = :status)
              and (:executionId is null or execution.executionId = :executionId)
            """)
    Page<AlertExecution> search(@Param("alertId") Long alertId, @Param("templateId") Long templateId, @Param("status") AlertExecutionStatus status, @Param("executionId") UUID executionId, Pageable pageable);
}
