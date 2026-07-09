package app.alertify.entity.repositories.custom;

import java.util.List;
import java.util.function.BiFunction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.MultiValueMap;

import app.alertify.entity.Alert;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@Repository
public class DynamicSearchAlert implements DynamicSearch<Alert> {

	@Autowired
	private DynamicSearchGeneric<Alert> dynamic;
	
	@Override
	public DynamicSearchResult<Alert> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<Alert> type, List<BiFunction<Root<Alert>, CriteriaQuery<Alert>, Predicate>> fixedPredicates) {
		return dynamic.customSearch(pageable, params, type, fixedPredicates);
	}

}
