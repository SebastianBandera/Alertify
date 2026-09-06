package app.alertify.jpa.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.procedures.model.ProcedureTemplateDefinition;

public interface ProcedureTemplateDefinitionRepository extends JpaRepository<ProcedureTemplateDefinition, Long> {
    Optional<ProcedureTemplateDefinition> findByTemplateKey(String templateKey);
}
