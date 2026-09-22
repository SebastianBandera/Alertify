package app.alertify.jpa.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.procedures.model.ProcedureTemplateOutputDefinition;

public interface ProcedureTemplateOutputDefinitionRepository extends JpaRepository<ProcedureTemplateOutputDefinition, Long> {
    List<ProcedureTemplateOutputDefinition> findAllByTemplate_TemplateKeyOrderByOutputOrderAscIdAsc(String templateKey);
    List<ProcedureTemplateOutputDefinition> findAllByTemplate_IdOrderByOutputOrderAscIdAsc(Long templateId);
}
