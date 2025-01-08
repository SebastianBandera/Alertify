package app.alertify.entity.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.alertify.entity.Alert;

@Repository
public interface AlertRepository extends JpaRepository<Alert, Long> {
	
	Page<Alert> findByActiveTrue(Pageable pageable);
	
}
