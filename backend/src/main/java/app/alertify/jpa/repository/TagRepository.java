package app.alertify.jpa.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;

public interface TagRepository extends JpaRepository<Tag, Long>, JpaSpecificationExecutor<Tag> {

    Optional<Tag> findByIdAndScope(Long id, TagScope scope);

    List<Tag> findAllByIdInAndScope(Collection<Long> ids, TagScope scope);

    List<Tag> findAllByScope(TagScope scope);

    Page<Tag> findAllByScope(TagScope scope, Pageable pageable);

    boolean existsByScopeAndNameIgnoreCase(TagScope scope, String name);

    boolean existsByScopeAndNameIgnoreCaseAndIdNot(TagScope scope, String name, Long id);
}
