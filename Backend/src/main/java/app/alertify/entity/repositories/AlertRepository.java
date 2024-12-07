package app.alertify.entity.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.alertify.entity.Alert;

@Repository
public interface AlertRepository extends JpaRepository<Alert, Long> {
	
	List<Alert> findByActiveTrue();
}
