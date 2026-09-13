package app.alertify.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import app.alertify.jpa.entity.SecretBinaryValue;

public interface SecretBinaryValueRepository extends JpaRepository<SecretBinaryValue, Long> { }
