package app.alertify.jpa.specification;

import java.util.Set;

import org.springframework.data.jpa.domain.Specification;

import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.Tag;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;

public final class ApplicationConfigurationSpecifications {

    private ApplicationConfigurationSpecifications() {
    }

    public static Specification<ApplicationConfiguration> hasAnyTagId(Set<Long> tagIds) {
        return (root, query, cb) -> {
            query.distinct(true);
            Join<ApplicationConfiguration, Tag> tags = root.join("tags", JoinType.INNER);
            return tags.get("id").in(tagIds);
        };
    }
}
