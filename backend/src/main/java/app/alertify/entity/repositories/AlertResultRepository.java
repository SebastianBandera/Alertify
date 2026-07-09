package app.alertify.entity.repositories;

import java.util.Date;

import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import app.alertify.entity.Alert;
import app.alertify.entity.AlertResult;

@Repository
@Primary
public interface AlertResultRepository extends JpaRepository<AlertResult, Long> {
	
	Page<AlertResult> findByActiveTrue(Pageable pageable);
	
    @Query("SELECT ar FROM AlertResult ar WHERE ar.alert = :alert AND ar.active = true and ar.needsReview = :needsReview")
    Page<AlertResult> getAlertsResultByAlert(@Param("alert") Alert alert, @Param("needsReview") boolean needsReview, Pageable pageable);
    
    @Query("SELECT MAX(ar.dateIni) FROM AlertResult ar WHERE ar.active = true and ar.alert.id = :alertId and ar.statusResult.name = 'success'")
    Date findLastSuccessDateAlertResultByAlert(@Param("alertId") Long alertId);
    
    @Query("SELECT MAX(ar.dateIni) FROM AlertResult ar WHERE ar.active = true and ar.alert.id = :alertId and ar.statusResult.name != 'success'")
    Date findLastIssueDateAlertResultByAlert(@Param("alertId") Long alertId);
}
