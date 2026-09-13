package app.alertify.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import app.alertify.jpa.entity.ConfigurationBinaryValue;

public interface ConfigurationBinaryValueRepository extends JpaRepository<ConfigurationBinaryValue, Long> { }
