package app.alertify.jpa.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.procedures.execution.ProcedureExecutionStatus;
import app.alertify.procedures.model.ProcedureExecution;

public interface ProcedureExecutionRepository extends JpaRepository<ProcedureExecution, Long> {
    Optional<ProcedureExecution> findByExecutionId(UUID executionId);
    Page<ProcedureExecution> findAllByExecutionId(UUID executionId, Pageable pageable);
    Page<ProcedureExecution> findAllByProcedure_Id(Long procedureId, Pageable pageable);
    Page<ProcedureExecution> findAllByStatus(ProcedureExecutionStatus status, Pageable pageable);
    Page<ProcedureExecution> findAllByProcedure_IdAndStatus(Long procedureId, ProcedureExecutionStatus status, Pageable pageable);
    long countByProcedure_Id(Long procedureId);
    boolean existsByProcedure_IdAndStatus(Long procedureId, ProcedureExecutionStatus status);
    void deleteAllByProcedure_Id(Long procedureId);
}
