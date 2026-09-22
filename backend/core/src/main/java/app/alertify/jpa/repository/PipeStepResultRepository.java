package app.alertify.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.pipes.model.PipeStepResult;

public interface PipeStepResultRepository extends JpaRepository<PipeStepResult, Long> {
}
