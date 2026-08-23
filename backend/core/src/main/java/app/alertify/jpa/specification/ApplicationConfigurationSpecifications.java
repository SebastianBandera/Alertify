package app.alertify.jpa.specification;

import java.util.Locale;
import java.util.Set;

import org.hibernate.query.criteria.JpaExpression;
import org.springframework.data.jpa.domain.Specification;

import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.Tag;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

public final class ApplicationConfigurationSpecifications {

    private ApplicationConfigurationSpecifications() {
    }

    public static Specification<ApplicationConfiguration> valueContains(String value) {
        String search = "%" + escapeLikeValue(value.trim()) + "%";
        return (root, _, cb) -> {
            JpaExpression<?> jsonValue = (JpaExpression<?>) root.get("value");
            Expression<String> valueAsText = jsonValue.cast(String.class);
            Expression<String> normalizedValue = cb.lower(cb.function("unaccent", String.class, valueAsText));
            Expression<String> normalizedSearch = cb.lower(cb.function("unaccent", String.class, cb.literal(search)));
            return cb.like(normalizedValue, normalizedSearch, '\\');
        };
    }

    public static Specification<ApplicationConfiguration> nameNotEqualIgnoreCase(String name) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        return (root, _, cb) -> cb.notEqual(cb.lower(root.get("name")), normalizedName);
    }

    private static String escapeLikeValue(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static Specification<ApplicationConfiguration> hasAnyTagId(Set<Long> tagIds) {
        return (root, query, _) -> {
            query.distinct(true);
            Join<ApplicationConfiguration, Tag> tags = root.join("tags", JoinType.INNER);
            return tags.get("id").in(tagIds);
        };
    }

    public static Specification<ApplicationConfiguration> hasAllTagIds(Set<Long> tagIds) {
        return (root, query, cb) -> {
            Subquery<Long> matchingTagCount = query.subquery(Long.class);
            Root<ApplicationConfiguration> configuration = matchingTagCount.from(ApplicationConfiguration.class);
            Join<ApplicationConfiguration, Tag> tags = configuration.join("tags", JoinType.INNER);

            matchingTagCount.select(cb.countDistinct(tags.get("id")));
            matchingTagCount.where(
                    cb.equal(configuration.get("id"), root.get("id")),
                    tags.get("id").in(tagIds)
            );
            return cb.equal(matchingTagCount, (long) tagIds.size());
        };
    }
}
