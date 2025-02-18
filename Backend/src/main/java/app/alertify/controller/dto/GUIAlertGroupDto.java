package app.alertify.controller.dto;

import java.util.Objects;

public class GUIAlertGroupDto {

	  private Long id;

	  private String name;
	  
	  private AlertDto alert;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
	
	public AlertDto getAlert() {
		return alert;
	}

	public void setAlert(AlertDto alert) {
		this.alert = alert;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		GUIAlertGroupDto other = (GUIAlertGroupDto) obj;
		return Objects.equals(id, other.id);
	}

	@Override
	public String toString() {
		return "GUIAlertGroupDto [id=" + id + ", name=" + name + ", idAlert=" + alert + "]";
	}
}
