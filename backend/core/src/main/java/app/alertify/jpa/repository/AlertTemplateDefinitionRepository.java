package app.alertify.jpa.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.alerts.model.AlertTemplateDefinition;

/**
 * Persistence gateway for the catalog of discovered alert templates.
 */
public interface AlertTemplateDefinitionRepository extends JpaRepository<AlertTemplateDefinition, Long> {

    Optional<AlertTemplateDefinition> findByTemplateKey(String templateKey);
}
