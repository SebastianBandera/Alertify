package app.alertify.jpa.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.jpa.entity.ApplicationLogSource;

public interface ApplicationLogSourceRepository
        extends JpaRepository<ApplicationLogSource, Short> {

    Optional<ApplicationLogSource> findByCode(String code);
}
