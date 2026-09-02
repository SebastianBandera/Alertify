package app.alertify.jpa.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import app.alertify.alerts.model.Alert;

public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByTagsId(Long tagId);

    @Override
    @EntityGraph(attributePaths = "template")
    Optional<Alert> findById(Long id);

    @EntityGraph(attributePaths = "template")
    List<Alert> findAllByEnabledTrue();

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
