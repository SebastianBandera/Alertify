package app.alertify.jpa.specification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretValueType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

class DynamicSpecificationTest {

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Test
    void preservesUnderscoresInExactStringFilters() {
        Root<ApplicationConfiguration> root = mock(Root.class);
        Path<Object> path = mock(Path.class);
        Expression<String> stringPath = mock(Expression.class);
        Expression<String> literal = mock(Expression.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate predicate = mock(Predicate.class);
        String name = "AI_SCHEMA_SMOKE_1791733841996_BOOLEAN";
        when(root.get("name")).thenReturn(path);
        when((Class) path.getJavaType()).thenReturn(String.class);
        when(path.as(String.class)).thenReturn(stringPath);
        when(cb.literal(name)).thenReturn(literal);
        when(cb.equal(stringPath, literal)).thenReturn(predicate);
        when(cb.or(any(Predicate[].class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("name", name);
        DynamicSpecification.<ApplicationConfiguration>from(params, Map.of(), Set.of("name"))
                .toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(cb).literal(name);
        verify(cb).equal(stringPath, literal);
        verify(cb, never()).like(any(Expression.class), any(Expression.class), any(Character.class));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Test
    void treatsEnumConstantsWithUnderscoresAsEqualityNotRanges() {
        Root<ApplicationSecret> root = mock(Root.class);
        Path<Object> path = mock(Path.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate predicate = mock(Predicate.class);
        when(root.get("valueType")).thenReturn(path);
        when((Class) path.getJavaType()).thenReturn(SecretValueType.class);
        when(cb.equal(path, SecretValueType.DB_SECRET)).thenReturn(predicate);
        when(cb.or(any(Predicate[].class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("valueType", "DB_SECRET");
        DynamicSpecification.<ApplicationSecret>from(params, Map.of(), Set.of("valueType"))
                .toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(cb).equal(path, SecretValueType.DB_SECRET);
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(Comparable.class));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Test
    void rejectsUnknownEnumConstants() {
        Root<ApplicationSecret> root = mock(Root.class);
        Path<Object> path = mock(Path.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        when(root.get("valueType")).thenReturn(path);
        when((Class) path.getJavaType()).thenReturn(SecretValueType.class);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("valueType", "PLAIN_TEXT");

        assertThatThrownBy(() -> DynamicSpecification.<ApplicationSecret>from(params, Map.of(), Set.of("valueType"))
                .toPredicate(root, mock(CriteriaQuery.class), cb))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("valueType");
    }
}
