package app.alertify.jpa.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import app.alertify.alerts.model.Alert;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    @Override
    @EntityGraph(attributePaths = "template")
    Page<Alert> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "template")
    Page<Alert> findAllByNameContainingIgnoreCase(String name, Pageable pageable);

    @EntityGraph(attributePaths = "template")
    Page<Alert> findAllByTemplate_Id(Long templateId, Pageable pageable);

    @EntityGraph(attributePaths = "template")
    Page<Alert> findAllByTemplate_IdAndNameContainingIgnoreCase(
            Long templateId, String name, Pageable pageable
    );

    @Query("""
        select alert.template.id as templateId, count(alert.id) as alertCount
        from Alert alert
        group by alert.template.id
        """)
    List<TemplateAlertCount> countAlertsByTemplate();

    interface TemplateAlertCount {

        Long getTemplateId();

        long getAlertCount();
    }
}
