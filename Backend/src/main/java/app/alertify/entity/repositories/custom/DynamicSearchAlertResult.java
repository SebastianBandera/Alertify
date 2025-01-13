package app.alertify.entity.repositories.custom;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.MultiValueMap;

import app.alertify.entity.AlertResult;

@Repository
public class DynamicSearchAlertResult implements DynamicSearch<AlertResult> {

	@Autowired
	private DynamicSearchGeneric<AlertResult> dynamic;
	
	@Override
	public Page<AlertResult> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<AlertResult> type) {
		params.remove("page");
		return dynamic.customSearch(pageable, params, type);
	}

}
