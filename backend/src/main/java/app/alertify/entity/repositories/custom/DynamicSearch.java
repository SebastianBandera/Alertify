package app.alertify.entity.repositories.custom;

import java.util.List;
import java.util.function.BiFunction;

import org.springframework.data.domain.Pageable;
import org.springframework.util.MultiValueMap;

import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

public interface DynamicSearch<T> {

	DynamicSearchResult<T> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<T> type, List<BiFunction<Root<T>, CriteriaQuery<T>, Predicate>> fixedPredicates);
}
