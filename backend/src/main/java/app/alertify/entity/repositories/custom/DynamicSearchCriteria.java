package app.alertify.entity.repositories.custom;

public class DynamicSearchCriteria<T> {

	private final T value;
	private final Criteria criteria;
	
	public DynamicSearchCriteria(T value, Criteria criteria) {
		this.value = value;
		this.criteria = criteria;
	}

	public T getValue() {
		return value;
	}

	public Criteria getCriteria() {
		return criteria;
	}
	
	@Override
	public String toString() {
		return "DynamicSearchCriteria [value=" + value + ", criteria=" + criteria + "]";
	}

	public enum Criteria {
		NULL,
		EQUAL,
		DISTINCT,
		LESS,
		LESS_EQUAL,
		GREATER,
		GREATER_EQUAL
	}
}
