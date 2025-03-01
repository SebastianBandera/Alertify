package app.alertify.controller.dto;

import java.time.Duration;
import java.util.Objects;

public class AlertDto {

	private Long id;
	  
	private String name;

	private Duration periodicity;
	
	private String control;
	
	private int version;

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

	public Duration getPeriodicity() {
		return periodicity;
	}

	public void setPeriodicity(Duration periodicity) {
		this.periodicity = periodicity;
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
		AlertDto other = (AlertDto) obj;
		return Objects.equals(id, other.id);
	}

	@Override
	public String toString() {
		return "AlertDto [id=" + id + ", name=" + name + ", periodicity=" + periodicity + "]";
	}

	public String getControl() {
		return control;
	}

	public void setControl(String control) {
		this.control = control;
	}

	public int getVersion() {
		return version;
	}

	public void setVersion(int version) {
		this.version = version;
	}
	  
	  
}
