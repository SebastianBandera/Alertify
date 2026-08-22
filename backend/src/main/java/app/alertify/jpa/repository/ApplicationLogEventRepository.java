package app.alertify.jpa.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.jpa.entity.ApplicationLogEvent;

public interface ApplicationLogEventRepository extends JpaRepository<ApplicationLogEvent, Short> {

    Optional<ApplicationLogEvent> findByCode(String code);
}
