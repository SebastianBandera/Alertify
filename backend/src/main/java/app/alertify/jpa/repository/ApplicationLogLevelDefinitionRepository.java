package app.alertify.jpa.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.jpa.entity.ApplicationLogLevelDefinition;

public interface ApplicationLogLevelDefinitionRepository
        extends JpaRepository<ApplicationLogLevelDefinition, Short> {

    Optional<ApplicationLogLevelDefinition> findByCode(String code);
}
