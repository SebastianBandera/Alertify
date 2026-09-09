package app.alertify.jpa.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.jpa.entity.SystemConfiguration;

/**
 * Persistence gateway for backend-owned system configurations, including the
 * case-insensitive name lookup used to derive the secrets encryption key.
 */
public interface SystemConfigurationRepository extends JpaRepository<SystemConfiguration, Long> {

    Optional<SystemConfiguration> findByNameIgnoreCase(String name);
}
