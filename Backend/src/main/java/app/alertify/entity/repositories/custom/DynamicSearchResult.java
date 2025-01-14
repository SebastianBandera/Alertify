package app.alertify.entity.repositories.custom;

import java.util.List;

import org.springframework.data.domain.Page;

public class DynamicSearchResult<T> {
	
	private final Page<T> page;
	
	private final List<Exception> exceptions;

	public DynamicSearchResult(Page<T> page, List<Exception> exceptions) {
		this.page = page;
		this.exceptions = exceptions;
	}

	public Page<T> getPage() {
		return page;
	}

	public List<Exception> getExceptions() {
		return exceptions;
	}

	@Override
	public String toString() {
		return "DynamicSearchResult [page=" + page + ", exceptions=" + exceptions + "]";
	}
}
