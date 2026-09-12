package app.alertify.jpa.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import app.alertify.procedures.model.Procedure;

public interface ProcedureRepository extends JpaRepository<Procedure, Long>, JpaSpecificationExecutor<Procedure> {
    Optional<Procedure> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
    List<Procedure> findAllByEnabledTrue();
    long countByTemplate_Id(Long templateId);
    boolean existsByTagsId(Long tagId);

    @Query("""
        select procedure.template.id as templateId, count(procedure.id) as procedureCount
        from Procedure procedure
        group by procedure.template.id
        """)
    List<TemplateProcedureCount> countProceduresByTemplate();

    interface TemplateProcedureCount {
        Long getTemplateId();
        long getProcedureCount();
    }
}
