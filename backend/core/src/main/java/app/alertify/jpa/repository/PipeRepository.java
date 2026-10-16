package app.alertify.jpa.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

import app.alertify.pipes.model.Pipe;

public interface PipeRepository extends JpaRepository<Pipe, Long> {
    java.util.List<app.alertify.alerts.template.ParameterBindingMetadata.Executable> findAllProjectedBy();
    Optional<app.alertify.alerts.template.ParameterBindingMetadata.Executable> findProjectedById(Long id);
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
    boolean existsByTagsId(Long tagId);
    Page<Pipe> findAllByNameContainingIgnoreCase(String name, Pageable pageable);

    // Join only the ordered steps collection. Joining tags or bindings as well
    // multiplies rows and duplicates entries in the steps bag. Those collections
    // are loaded separately inside the caller's transaction.
    @EntityGraph(attributePaths = { "steps", "steps.alert", "steps.procedure" })
    @Query("select p from Pipe p where p.id = :id")
    Optional<Pipe> findDetailedById(Long id);
}
