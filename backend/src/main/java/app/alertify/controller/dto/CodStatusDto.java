package app.alertify.controller.dto;

import java.util.Objects;

public class CodStatusDto {

	  private Long id;
	  
	  private String name;

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
		CodStatusDto other = (CodStatusDto) obj;
		return Objects.equals(id, other.id);
	}

	@Override
	public String toString() {
		return "CodStatusDto [id=" + id + ", name=" + name + "]";
	}
}
