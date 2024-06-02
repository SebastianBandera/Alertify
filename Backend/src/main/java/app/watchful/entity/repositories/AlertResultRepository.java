package app.watchful.entity.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.watchful.entity.AlertResult;

@Repository
public interface AlertResultRepository extends JpaRepository<AlertResult, Long> {

}
