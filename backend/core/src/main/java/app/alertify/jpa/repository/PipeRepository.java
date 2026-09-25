package app.alertify.jpa.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

import app.alertify.pipes.model.Pipe;

public interface PipeRepository extends JpaRepository<Pipe, Long> {
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
    boolean existsByTagsId(Long tagId);
    Page<Pipe> findAllByNameContainingIgnoreCase(String name, Pageable pageable);

    @EntityGraph(attributePaths = { "steps", "steps.alert", "steps.procedure", "steps.bindings", "tags",
            "steps.bindings.targetParameter", "steps.bindings.sourceStep", "steps.bindings.sourceOutput" })
    @Query("select p from Pipe p where p.id = :id")
    Optional<Pipe> findDetailedById(Long id);
}
