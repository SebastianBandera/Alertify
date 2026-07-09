package app.alertify.entity.repositories;

import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import app.alertify.entity.Alert;

@Repository
@Primary
public interface AlertRepository extends JpaRepository<Alert, Long> {
	
	Page<Alert> findByActiveTrue(Pageable pageable);

	@Query("SELECT a FROM Alert a WHERE NOT EXISTS (SELECT 1 FROM GUIAlertGroup gag WHERE gag.alert = a)")
	Page<Alert> findAlertsNotInAnyGroup(Pageable pageable);
}
