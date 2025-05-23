package app.alertify.entity.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.alertify.entity.ConfigTimestamp;

@Repository
public interface ConfigTimestampRepository extends JpaRepository<ConfigTimestamp, String> {

}
