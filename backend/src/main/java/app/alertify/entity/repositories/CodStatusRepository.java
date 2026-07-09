package app.alertify.entity.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.alertify.entity.CodStatus;

@Repository
public interface CodStatusRepository extends JpaRepository<CodStatus, Long> {

}
