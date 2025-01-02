package app.alertify.entity.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.alertify.entity.ConfigInt;

@Repository
public interface ConfigIntRepository extends JpaRepository<ConfigInt, String> {

}
