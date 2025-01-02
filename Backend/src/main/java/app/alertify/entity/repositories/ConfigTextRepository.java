package app.alertify.entity.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.alertify.entity.ConfigText;

@Repository
public interface ConfigTextRepository extends JpaRepository<ConfigText, String> {

}
