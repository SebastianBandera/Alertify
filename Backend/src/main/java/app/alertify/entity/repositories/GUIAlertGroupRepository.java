package app.alertify.entity.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import app.alertify.entity.GUIAlertGroup;

@Repository
public interface GUIAlertGroupRepository extends JpaRepository<GUIAlertGroup, Long> {

	List<GUIAlertGroup> findByActiveTrue();
	
    @Query("SELECT gag FROM GUIAlertGroup gag JOIN FETCH gag.alert WHERE gag.active = true")
    List<GUIAlertGroup> findByActiveTrueEager();
}
