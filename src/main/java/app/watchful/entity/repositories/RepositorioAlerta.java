package app.watchful.entity.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import app.watchful.entity.Alerta;

@Repository
public interface RepositorioAlerta extends JpaRepository<Alerta, Long> {

}
