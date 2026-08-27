package app.alertify.jpa.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.alerts.model.AlertTemplateParameterDefinition;

/**
 * Persistence gateway for the parameter metadata of alert templates.
 */
public interface AlertTemplateParameterDefinitionRepository extends JpaRepository<AlertTemplateParameterDefinition, Long> {

    List<AlertTemplateParameterDefinition> findAllByTemplate_TemplateKey(String templateKey);
}
