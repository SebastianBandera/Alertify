package app.alertify.jpa.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.alerts.model.Alert;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    @Override
    @EntityGraph(attributePaths = "template")
    Page<Alert> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "template")
    Page<Alert> findAllByNameContainingIgnoreCase(String name, Pageable pageable);
}
