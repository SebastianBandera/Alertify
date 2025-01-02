package app.alertify.entity;

import java.util.Objects;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

@Entity(name = "GUIAlertGroup")
@Table(schema = "gui", name = "alert_group")
public class GUIAlertGroup {

	  @Id
	  @GeneratedValue(strategy=GenerationType.IDENTITY)
	  private Long id;

	  @Column(name="name", nullable = false)
	  private String name;
	  
	  @ManyToOne(fetch = FetchType.EAGER)
	  @JoinColumn(name = "id_alert", nullable = false)
	  private Alert alert;
	  
	  @Column(name="active")
	  private boolean active;

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

	public Alert getAlert() {
		return alert;
	}

	public void setAlert(Alert alert) {
		this.alert = alert;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
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
		GUIAlertGroup other = (GUIAlertGroup) obj;
		return Objects.equals(id, other.id);
	}

	@Override
	public String toString() {
		return "GUIAlertGroup [id=" + id + ", name=" + name + ", alert=" + alert + ", active=" + active + "]";
	}
}
