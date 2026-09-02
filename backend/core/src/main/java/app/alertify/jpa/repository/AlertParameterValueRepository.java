package app.alertify.jpa.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import app.alertify.alerts.model.AlertParameterValue;

public interface AlertParameterValueRepository extends JpaRepository<AlertParameterValue, Long> {

    @EntityGraph(attributePaths = { "templateParameter", "configuration", "secret" })
    @Query("""
        select value from AlertParameterValue value
        where value.alert.id = :alertId
        order by value.templateParameter.parameterOrder, value.templateParameter.id
        """)
    List<AlertParameterValue> findAllByAlertIdOrdered(Long alertId);
}
