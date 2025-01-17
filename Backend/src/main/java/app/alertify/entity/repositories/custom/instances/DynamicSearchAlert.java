package app.alertify.entity.repositories.custom.instances;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.MultiValueMap;

import app.alertify.entity.Alert;
import app.alertify.entity.repositories.custom.DynamicSearch;
import app.alertify.entity.repositories.custom.DynamicSearchGeneric;
import app.alertify.entity.repositories.custom.DynamicSearchResult;

@Repository("DynamicSearchAlert")
public class DynamicSearchAlert implements DynamicSearch<Alert> {

	@Autowired
	private DynamicSearchGeneric<Alert> dynamic;
	
	@Override
	public DynamicSearchResult<Alert> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<Alert> type) {
		return dynamic.customSearch(pageable, params, type);
	}

}
