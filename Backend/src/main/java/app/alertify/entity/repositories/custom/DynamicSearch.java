package app.alertify.entity.repositories.custom;

import org.springframework.data.domain.Pageable;
import org.springframework.util.MultiValueMap;

public interface DynamicSearch<T> {

	DynamicSearchResult<T> customSearch(Pageable pageable, MultiValueMap<String, String> params, Class<T> type);
}
