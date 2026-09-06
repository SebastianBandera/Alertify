package app.alertify.jpa.specification;

import java.util.Set;

import org.springframework.data.jpa.domain.Specification;

import app.alertify.jpa.entity.Tag;
import app.alertify.procedures.model.Procedure;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Reusable criteria fragments for the procedure search. Matching any tag joins
 * and de-duplicates the result, while matching all tags counts the distinct
 * matches in a subquery so paging stays correct.
 */
public final class ProcedureSpecifications {

    private ProcedureSpecifications() {
    }

    public static Specification<Procedure> nameContains(String value) {
        return (root, _, cb) -> cb.like(cb.lower(root.get("name")), "%" + value.toLowerCase(java.util.Locale.ROOT) + "%");
    }

    public static Specification<Procedure> hasTemplateId(Long value) {
        return (root, _, cb) -> cb.equal(root.get("template").get("id"), value);
    }

    public static Specification<Procedure> hasAnyTagId(Set<Long> ids) {
        return (root, query, _) -> {
            query.distinct(true);
            Join<Procedure, Tag> tags = root.join("tags", JoinType.INNER);
            return tags.get("id").in(ids);
        };
    }

    public static Specification<Procedure> hasAllTagIds(Set<Long> ids) {
        return (root, query, cb) -> {
            Subquery<Long> count = query.subquery(Long.class);
            Root<Procedure> procedure = count.from(Procedure.class);
            Join<Procedure, Tag> tags = procedure.join("tags", JoinType.INNER);
            count.select(cb.countDistinct(tags.get("id")));
            count.where(cb.equal(procedure.get("id"), root.get("id")), tags.get("id").in(ids));
            return cb.equal(count, (long) ids.size());
        };
    }
}
