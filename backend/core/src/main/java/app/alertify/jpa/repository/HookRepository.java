package app.alertify.jpa.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import app.alertify.hooks.model.Hook;

public interface HookRepository extends JpaRepository<Hook, Long> {
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    @Override
    @EntityGraph(attributePaths = { "tokenSecret", "targets", "targets.alert", "targets.procedure" })
    Optional<Hook> findById(Long id);

    @Override
    @EntityGraph(attributePaths = { "tokenSecret", "targets", "targets.alert", "targets.procedure" })
    List<Hook> findAll(Sort sort);

    @EntityGraph(attributePaths = { "tokenSecret", "targets", "targets.alert", "targets.procedure" })
    Optional<Hook> findByPublicIdAndEnabledTrue(UUID publicId);

    Page<Hook> findAllByNameContainingIgnoreCase(String name, Pageable pageable);
}
