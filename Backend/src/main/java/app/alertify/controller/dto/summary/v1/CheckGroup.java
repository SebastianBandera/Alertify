package app.alertify.controller.dto.summary.v1;

import java.util.List;
import java.util.Objects;

public class CheckGroup {
    
	private String name;
    private List<Check> checks;
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public List<Check> getChecks() {
		return checks;
	}
	public void setChecks(List<Check> checks) {
		this.checks = checks;
	}
	@Override
	public int hashCode() {
		return Objects.hash(checks, name);
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CheckGroup other = (CheckGroup) obj;
		return Objects.equals(checks, other.checks) && Objects.equals(name, other.name);
	}
	@Override
	public String toString() {
		return "CheckGroup [name=" + name + ", checks=" + checks + "]";
	}
    
    
}
