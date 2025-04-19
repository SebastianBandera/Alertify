package app.alertify.entity.repositories.custom.instances;

import java.util.List;
import java.util.function.BiFunction;

import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.MultiValueMap;

import app.alertify.entity.AlertResult;
import app.alertify.entity.repositories.custom.DynamicSearch;
import app.alertify.entity.repositories.custom.DynamicSearchGeneric;
import app.alertify.entity.repositories.custom.DynamicSearchResult;

@Repository("DynamicSearchAlertResult")
public class DynamicSearchAlertResult implements DynamicSearch<AlertResult> {

	@Autowired
	private DynamicSearchGeneric<AlertResult> dynamic;
	
	@Override
	public DynamicSearchResult<AlertResult> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<AlertResult> type, List<BiFunction<Root<AlertResult>, CriteriaQuery<AlertResult>, Predicate>> fixedPredicates) {
		return dynamic.customSearch(pageable, params, type, fixedPredicates);
	}

}
