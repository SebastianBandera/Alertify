package app.alertify.controller.dto.summary.v1;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Objects;

public class Check {

    private String name;
    private Date lastSuccess;
    private Duration period;
    private List<CheckError> errors;
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public Date getLastSuccess() {
		return lastSuccess;
	}
	public void setLastSuccess(Date lastSuccess) {
		this.lastSuccess = lastSuccess;
	}
	public Duration getPeriod() {
		return period;
	}
	public void setPeriod(Duration period) {
		this.period = period;
	}
	public List<CheckError> getErrors() {
		return errors;
	}
	public void setErrors(List<CheckError> errors) {
		this.errors = errors;
	}
	@Override
	public int hashCode() {
		return Objects.hash(errors, lastSuccess, name, period);
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Check other = (Check) obj;
		return Objects.equals(errors, other.errors) && Objects.equals(lastSuccess, other.lastSuccess)
				&& Objects.equals(name, other.name) && Objects.equals(period, other.period);
	}
	@Override
	public String toString() {
		return "Check [name=" + name + ", lastSuccess=" + lastSuccess + ", period=" + period + ", errors=" + errors
				+ "]";
	}

	
}
