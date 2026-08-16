package app.alertify.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import app.alertify.jpa.entity.ApplicationConfiguration;

public interface ApplicationConfigurationRepository
        extends JpaRepository<ApplicationConfiguration, Long>,
                JpaSpecificationExecutor<ApplicationConfiguration> {

    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
    boolean existsByTagsId(Long tagId);
}
