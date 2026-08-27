package app.alertify.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.alerts.model.AlertTemplateDefinition;

/**
 * Persistence gateway for the catalog of discovered alert templates.
 */
public interface AlertTemplateDefinitionRepository extends JpaRepository<AlertTemplateDefinition, String> {
}
