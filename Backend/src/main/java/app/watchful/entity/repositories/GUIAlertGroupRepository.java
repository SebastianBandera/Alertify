package app.watchful.entity.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.watchful.entity.GUIAlertGroup;

@Repository
public interface GUIAlertGroupRepository extends JpaRepository<GUIAlertGroup, Long> {

}
