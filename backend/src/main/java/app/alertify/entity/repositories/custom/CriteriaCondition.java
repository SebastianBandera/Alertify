package app.alertify.entity.repositories.custom;

import app.alertify.entity.repositories.custom.DynamicSearchCriteria.Criteria;

public class CriteriaCondition {
	private final String op;
	private final Criteria criteria;
	
	public CriteriaCondition(String op, Criteria criteria) {
		this.op = op;
		this.criteria = criteria;
	}

	public String getOp() {
		return op;
	}

	public Criteria getCriteria() {
		return criteria;
	}
}