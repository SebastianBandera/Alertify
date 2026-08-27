package app.alertify.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.alerts.model.AlertState;

public interface AlertStateRepository extends JpaRepository<AlertState, Long> {
}
