package app.alertify.jpa.specification;

import java.util.Set;

import org.springframework.data.jpa.domain.Specification;

import app.alertify.alerts.model.Alert;
import app.alertify.jpa.entity.Tag;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

public final class AlertSpecifications {

    private AlertSpecifications() {
    }

    public static Specification<Alert> nameContains(String name) {
        return (root, _, cb) -> cb.like(cb.lower(root.get("name")), "%" + name.toLowerCase(java.util.Locale.ROOT) + "%");
    }

    public static Specification<Alert> hasTemplateId(Long templateId) {
        return (root, _, cb) -> cb.equal(root.get("template").get("id"), templateId);
    }

    public static Specification<Alert> hasAnyTagId(Set<Long> tagIds) {
        return (root, query, _) -> {
            query.distinct(true);
            Join<Alert, Tag> tags = root.join("tags", JoinType.INNER);
            return tags.get("id").in(tagIds);
        };
    }

    public static Specification<Alert> hasAllTagIds(Set<Long> tagIds) {
        return (root, query, cb) -> {
            Subquery<Long> matchingTagCount = query.subquery(Long.class);
            Root<Alert> alert = matchingTagCount.from(Alert.class);
            Join<Alert, Tag> tags = alert.join("tags", JoinType.INNER);
            matchingTagCount.select(cb.countDistinct(tags.get("id")));
            matchingTagCount.where(
                    cb.equal(alert.get("id"), root.get("id")),
                    tags.get("id").in(tagIds)
            );
            return cb.equal(matchingTagCount, (long) tagIds.size());
        };
    }
}
