package app.alertify.jpa.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import app.alertify.jpa.entity.ApplicationConfiguration;

public interface ApplicationConfigurationRepository extends JpaRepository<ApplicationConfiguration, Long>, JpaSpecificationExecutor<ApplicationConfiguration> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByTagsId(Long tagId);

    Optional<ApplicationConfiguration> findByName(String name);

    Optional<ApplicationConfiguration> findByNameIgnoreCase(String name);

    @Query("select configuration.name from ApplicationConfiguration configuration order by lower(configuration.name), configuration.name")
    List<String> findAllNames();
}
