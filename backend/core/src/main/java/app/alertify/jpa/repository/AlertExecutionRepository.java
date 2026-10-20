package app.alertify.jpa.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.execution.AlertExecutionTrigger;
import app.alertify.alerts.model.AlertExecution;

public interface AlertExecutionRepository extends JpaRepository<AlertExecution, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select execution from AlertExecution execution where execution.id = :id")
    Optional<AlertExecution> lockById(@Param("id") Long id);

    @EntityGraph(attributePaths = { "alert", "alert.template" })
    @Query("select execution from AlertExecution execution where execution.alert.id = :alertId and execution.closed = false and execution.finishedAt >= :from and execution.finishedAt <= :to")
    List<AlertExecution> chartHistory(@Param("alertId") long alertId, @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    long countByAlert_Id(Long alertId);

    boolean existsByAlert_IdAndStatusAndFinishedAtGreaterThanEqual(Long alertId, AlertExecutionStatus status, Instant finishedAt);

    Optional<AlertExecution> findFirstByAlert_IdAndFinishedAtIsNotNullOrderByFinishedAtDescIdDesc(Long alertId);

    Optional<AlertExecution> findFirstByAlert_IdAndTriggerAndFinishedAtIsNotNullOrderByFinishedAtDescIdDesc(Long alertId, AlertExecutionTrigger trigger);

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
            where (:filterAlertId = false or execution.alert.id = :alertId)
              and (:filterTemplateId = false or execution.alert.template.id = :templateId)
              and (:filterStatus = false or execution.status = :status)
              and (:filterExecutionId = false or execution.executionId = :executionId)
              and (:filterFrom = false or execution.finishedAt >= :from)
              and (:filterTo = false or execution.finishedAt <= :to)
            """)
    Page<AlertExecution> search(@Param("filterAlertId") boolean filterAlertId, @Param("alertId") Long alertId, @Param("filterTemplateId") boolean filterTemplateId, @Param("templateId") Long templateId, @Param("filterStatus") boolean filterStatus, @Param("status") AlertExecutionStatus status, @Param("filterExecutionId") boolean filterExecutionId, @Param("executionId") UUID executionId, @Param("filterFrom") boolean filterFrom, @Param("from") Instant from, @Param("filterTo") boolean filterTo, @Param("to") Instant to, Pageable pageable);
}
