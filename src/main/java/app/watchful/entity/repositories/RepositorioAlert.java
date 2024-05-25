package app.watchful.entity.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.watchful.entity.Alert;

@Repository
public interface RepositorioAlert extends JpaRepository<Alert, Long> {

}
