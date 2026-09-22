package app.alertify.jpa.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.procedures.model.ProcedureArtifactMetadata;

public interface ProcedureArtifactMetadataRepository extends JpaRepository<ProcedureArtifactMetadata, Long> {
    List<ProcedureArtifactMetadata> findAllByExecution_ExecutionIdOrderByOutputKeyAsc(java.util.UUID executionId);
}
