package app.alertify.jpa.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import app.alertify.jpa.entity.ApplicationSecret;

/**
 * Persistence gateway for encrypted secrets and their metadata, including
 * case-insensitive name and tag-assignment queries.
 */
public interface ApplicationSecretRepository extends JpaRepository<ApplicationSecret, Long>, JpaSpecificationExecutor<ApplicationSecret> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByTagsId(Long tagId);

    Optional<ApplicationSecret> findByNameIgnoreCase(String name);
}
