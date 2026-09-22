package app.alertify.jpa.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.pipes.model.PipeExecution;
import app.alertify.pipes.model.PipeExecutionStatus;

public interface PipeExecutionRepository extends JpaRepository<PipeExecution, Long> {
    Optional<PipeExecution> findByExecutionId(UUID executionId);
    Page<PipeExecution> findAllByPipe_Id(Long pipeId, Pageable pageable);
    long countByPipe_Id(Long pipeId);
    boolean existsByPipe_IdAndStatus(Long pipeId, PipeExecutionStatus status);
}
