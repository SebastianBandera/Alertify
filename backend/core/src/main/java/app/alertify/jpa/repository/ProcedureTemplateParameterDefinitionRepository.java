package app.alertify.jpa.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;

public interface ProcedureTemplateParameterDefinitionRepository extends JpaRepository<ProcedureTemplateParameterDefinition, Long> {
    List<ProcedureTemplateParameterDefinition> findAllByTemplate_IdOrderByParameterOrderAscIdAsc(Long templateId);
    List<ProcedureTemplateParameterDefinition> findAllByTemplate_TemplateKey(String templateKey);
}
