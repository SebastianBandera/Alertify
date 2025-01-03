package app.alertify.entity.repositories;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import app.alertify.entity.Alert;
import app.alertify.entity.AlertResult;

@Repository
public interface AlertResultRepository extends JpaRepository<AlertResult, Long> {
	
	List<AlertResult> findByActiveTrue();
	
    @Query("SELECT ar FROM AlertResult ar WHERE ar.alert = :alert AND ar.active = true and ar.needs_review = :needsReview")
    List<AlertResult> getAlertsResultByAlert(@Param("alert") Alert alert, @Param("needsReview") boolean needsReview, Pageable pageable);
    
    
}
