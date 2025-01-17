package app.alertify.entity.repositories.custom.instances;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.MultiValueMap;

import app.alertify.entity.GUIAlertGroup;
import app.alertify.entity.repositories.custom.DynamicSearch;
import app.alertify.entity.repositories.custom.DynamicSearchGeneric;
import app.alertify.entity.repositories.custom.DynamicSearchResult;

@Repository("DynamicSearchGUIAlertGroupRepository")
public class DynamicSearchGUIAlertGroupRepository implements DynamicSearch<GUIAlertGroup> {

	@Autowired
	private DynamicSearchGeneric<GUIAlertGroup> dynamic;
	
	@Override
	public DynamicSearchResult<GUIAlertGroup> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<GUIAlertGroup> type) {
		return dynamic.customSearch(pageable, params, type);
	}

}
