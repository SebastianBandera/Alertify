package app.alertify.jpa.specification;

import java.util.Set;

import org.springframework.data.jpa.domain.Specification;

import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.Tag;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

public final class ApplicationSecretSpecifications {

    private ApplicationSecretSpecifications() {
    }

    public static Specification<ApplicationSecret> hasAnyTagId(Set<Long> tagIds) {
        return (root, query, _) -> {
            query.distinct(true);
            Join<ApplicationSecret, Tag> tags = root.join("tags", JoinType.INNER);
            return tags.get("id").in(tagIds);
        };
    }

    public static Specification<ApplicationSecret> hasAllTagIds(Set<Long> tagIds) {
        return (root, query, cb) -> {
            Subquery<Long> matchingTagCount = query.subquery(Long.class);
            Root<ApplicationSecret> secret = matchingTagCount.from(ApplicationSecret.class);
            Join<ApplicationSecret, Tag> tags = secret.join("tags", JoinType.INNER);

            matchingTagCount.select(cb.countDistinct(tags.get("id")));
            matchingTagCount.where(
                    cb.equal(secret.get("id"), root.get("id")),
                    tags.get("id").in(tagIds)
            );
            return cb.equal(matchingTagCount, (long) tagIds.size());
        };
    }
}
