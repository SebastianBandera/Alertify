package app.alertify.jpa.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import app.alertify.procedures.model.ProcedureParameterValue;

public interface ProcedureParameterValueRepository extends JpaRepository<ProcedureParameterValue, Long> {
    @Query("""
        select value from ProcedureParameterValue value
        join fetch value.templateParameter
        left join fetch value.configuration
        left join fetch value.secret
        left join fetch value.referencedProcedure
        where value.owner.id = :procedureId
        order by value.templateParameter.parameterOrder, value.id
        """)
    List<ProcedureParameterValue> findAllByOwnerIdOrdered(Long procedureId);

    long countByReferencedProcedure_Id(Long procedureId);
    void deleteAllByOwner_Id(Long procedureId);
}
