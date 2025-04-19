package app.alertify.entity.repositories.custom;

import java.util.List;
import java.util.function.BiFunction;

import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.springframework.data.domain.Pageable;
import org.springframework.util.MultiValueMap;

public interface DynamicSearch<T> {

	DynamicSearchResult<T> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<T> type, List<BiFunction<Root<T>, CriteriaQuery<T>, Predicate>> fixedPredicates);
}
