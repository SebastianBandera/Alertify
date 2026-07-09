package app.alertify.controller.dto.summary.v1;

import java.util.List;
import java.util.Objects;

public class CheckGroups {
	
	private List<CheckGroup> checkGroups;

	public List<CheckGroup> getCheckGroups() {
		return checkGroups;
	}

	public void setCheckGroups(List<CheckGroup> checkGroups) {
		this.checkGroups = checkGroups;
	}

	@Override
	public int hashCode() {
		return Objects.hash(checkGroups);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CheckGroups other = (CheckGroups) obj;
		return Objects.equals(checkGroups, other.checkGroups);
	}

	@Override
	public String toString() {
		return "CheckGroups [checkGroups=" + checkGroups + "]";
	}
}
