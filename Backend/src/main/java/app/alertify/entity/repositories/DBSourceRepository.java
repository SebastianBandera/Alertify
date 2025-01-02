package app.alertify.entity.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.alertify.entity.DbSource;

@Repository
public interface DBSourceRepository extends JpaRepository<DbSource, Long> {

}
