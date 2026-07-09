package app.alertify.entity.repositories;

import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.alertify.entity.GUIAlertGroup;

@Repository
@Primary
public interface GUIAlertGroupRepository extends JpaRepository<GUIAlertGroup, Long> {

	Page<GUIAlertGroup> findByActiveTrue(Pageable pageable);

}
