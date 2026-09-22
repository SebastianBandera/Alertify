package app.alertify.jpa.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.pipes.model.PipeStep;

public interface PipeStepRepository extends JpaRepository<PipeStep, Long> {
    List<PipeStep> findAllByPipe_IdOrderByPositionAsc(Long pipeId);
    long countByAlert_Id(Long alertId);
    long countByProcedure_Id(Long procedureId);
}
