package app.alertify.jpa.specification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.MultiValueMap;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;

/**
 * Builds allow-listed Spring Data specifications from HTTP query parameters,
 * providing a shared filtering language.
 */
public final class DynamicSpecification {

    private static final Set<String> RESERVED_PARAMS = Set.of(
            "page", "size", "sort", "tagId", "tagOperator"
    );

    private DynamicSpecification() {
    }

    public static <T> Specification<T> from(MultiValueMap<String, String> params) {
        return from(params, Map.of(), Set.of());
    }

    public static <T> Specification<T> from(MultiValueMap<String, String> params, Map<String, String> aliases, Set<String> allowedFields) {
        return (root, _, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            params.forEach((field, values) -> {
                if (RESERVED_PARAMS.contains(field) || values == null || values.isEmpty())
                    return;

                String entityField = aliases.getOrDefault(field, field);
                if (!allowedFields.isEmpty() && !allowedFields.contains(entityField)) {
                    throw new InvalidFilterException(field);
                }

                try {
                    Path<?> path = resolvePath(root, entityField);
                    Class<?> type = path.getJavaType();
                    List<Predicate> fieldPredicates = values.stream()
                            .map(value -> createPredicate(cb, path, type, value))
                            .toList();
                    predicates.add(cb.or(fieldPredicates.toArray(Predicate[]::new)));
                } catch (InvalidFilterException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    throw new InvalidFilterException(field, exception);
                }
            });

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate createPredicate(CriteriaBuilder cb, Path<?> path, Class<?> type, String rawValue) {
        String value = rawValue.trim();

        if ("=null".equalsIgnoreCase(value))
            return cb.isNull(path);
        if ("!=null".equalsIgnoreCase(value))
            return cb.isNotNull(path);
        if (type == String.class)
            return createStringPredicate(cb, path, value);
        if (isRange(value))
            return createRangePredicate(cb, path, type, value);
        if (value.startsWith("!="))
            return cb.notEqual(path, convert(value.substring(2), type));
        if (value.startsWith(">="))
            return greaterThanOrEqualTo(cb, path, convertComparable(value.substring(2), type));
        if (value.startsWith("<="))
            return lessThanOrEqualTo(cb, path, convertComparable(value.substring(2), type));
        if (value.startsWith(">"))
            return greaterThan(cb, path, convertComparable(value.substring(1), type));
        if (value.startsWith("<"))
            return lessThan(cb, path, convertComparable(value.substring(1), type));
        if (value.startsWith("="))
            value = value.substring(1);
        return cb.equal(path, convert(value, type));
    }

    private static Predicate createStringPredicate(CriteriaBuilder cb, Path<?> path, String value) {
        boolean flexible = value.startsWith("~");
        if (flexible)
            value = value.substring(1);

        boolean distinct = value.startsWith("!=");
        if (distinct)
            value = value.substring(2);
        else if (value.startsWith("="))
            value = value.substring(1);

        boolean wildcard = value.contains("*");
        String search = escapeLikeValue(value).replace("*", "%");

        Expression<String> fieldExpression;
        Expression<String> valueExpression;

        if (flexible) {
            fieldExpression = cb.lower(cb.function("unaccent", String.class, path.as(String.class)));
            valueExpression = cb.lower(cb.function("unaccent", String.class, cb.literal(search)));
        } else {
            fieldExpression = path.as(String.class);
            valueExpression = cb.literal(search);
        }

        Predicate predicate = wildcard
                ? cb.like(fieldExpression, valueExpression, '\\')
                : cb.equal(fieldExpression, valueExpression);
        return distinct ? cb.not(predicate) : predicate;
    }

    private static String escapeLikeValue(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean isRange(String value) {
        int separator = value.indexOf('_');
        return separator > 0 && separator < value.length() - 1;
    }

    private static Predicate createRangePredicate(CriteriaBuilder cb, Path<?> path, Class<?> type, String value) {
        int separator = value.indexOf('_');
        Comparable<?> from = convertComparable(value.substring(0, separator), type);
        Comparable<?> to = convertComparable(value.substring(separator + 1), type);
        return cb.and(
                greaterThanOrEqualTo(cb, path, from),
                lessThanOrEqualTo(cb, path, to)
        );
    }

    private static Path<?> resolvePath(Path<?> root, String field) {
        Path<?> path = root;
        for (String part : field.split("\\."))
            path = path.get(part);
        return path;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Predicate greaterThan(CriteriaBuilder cb, Path<?> path, Comparable<?> value) {
        return cb.greaterThan((Path) path, (Comparable) value);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Predicate greaterThanOrEqualTo(CriteriaBuilder cb, Path<?> path, Comparable<?> value) {
        return cb.greaterThanOrEqualTo((Path) path, (Comparable) value);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Predicate lessThan(CriteriaBuilder cb, Path<?> path, Comparable<?> value) {
        return cb.lessThan((Path) path, (Comparable) value);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Predicate lessThanOrEqualTo(CriteriaBuilder cb, Path<?> path, Comparable<?> value) {
        return cb.lessThanOrEqualTo((Path) path, (Comparable) value);
    }

    private static Comparable<?> convertComparable(String value, Class<?> type) {
        Object converted = convert(value.trim(), type);
        if (!(converted instanceof Comparable<?> comparable)) {
            throw new IllegalArgumentException("Type does not support comparison: " + type.getSimpleName());
        }
        return comparable;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object convert(String value, Class<?> type) {
        value = value.trim();
        if (type == Integer.class || type == int.class)
            return Integer.valueOf(value);
        if (type == Long.class || type == long.class)
            return Long.valueOf(value);
        if (type == Double.class || type == double.class)
            return Double.valueOf(value);
        if (type == Float.class || type == float.class)
            return Float.valueOf(value);
        if (type == Boolean.class || type == boolean.class)
            return Boolean.valueOf(value);
        if (type == LocalDate.class)
            return LocalDate.parse(value);
        if (type == LocalDateTime.class)
            return LocalDateTime.parse(value);
        if (type == Instant.class)
            return Instant.parse(value);
        if (type.isEnum())
            return Enum.valueOf((Class<? extends Enum>) type, value);
        throw new IllegalArgumentException("Unsupported filter type: " + type.getSimpleName());
    }
}
