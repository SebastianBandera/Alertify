package app.alertify.entity.repositories.custom;

import java.util.List;

import org.springframework.data.domain.Page;

public class DynamicSearchResultDto<T> {
	
	private final Page<T> page;
	
	private final List<String> errorMessages;

	public DynamicSearchResultDto(Page<T> page, List<String> errorMessages) {
		this.page = page;
		this.errorMessages = errorMessages;
	}

	public Page<T> getPage() {
		return page;
	}

	public List<String> getErrorMessages() {
		return errorMessages;
	}

	@Override
	public String toString() {
		return "DynamicSearchResult [page=" + page + ", errorMessages=" + errorMessages + "]";
	}
}
