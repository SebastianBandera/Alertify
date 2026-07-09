package app.alertify.entity;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tools.jackson.databind.ObjectMapper;

@Entity(name = "Alert")
@Table(schema = "alert", name = "alerts")
public class Alert {
	
	private static final Logger log = LoggerFactory.getLogger(Alert.class);

	  @Id
	  @GeneratedValue(strategy=GenerationType.IDENTITY)
	  private Long id;
	  
	  @Column(name="name", nullable = false)
	  private String name;

	  @Column(name="control", nullable = false)
	  private String control;

	  @JdbcTypeCode(SqlTypes.JSON)
	  @Column(name="params", columnDefinition = "jsonb")
	  private String params;

	  @Column(name="periodicity", columnDefinition = "interval")
	  private Duration periodicity;
	  
	  @Column(name="active")
	  private boolean active;
	  
	  @Column(name="version")
	  private int version;
	  
	  @SuppressWarnings("unchecked")
	  public Map<String,Object> getParametrosMap() {
			try {
				return new ObjectMapper().readValue(params, HashMap.class);
			} catch (Exception e) {
				log.error("error getParametrosMap()", e);
				return new HashMap<>();
			}
	  }
	  
	  @Override
	  public boolean equals(Object o) {
	      if (this == o) return true;
	      if (o == null || getClass() != o.getClass()) return false;
	      Alert myEntity = (Alert) o;
	      return id != null && id.equals(myEntity.id);
	  }

	  @Override
	  public int hashCode() {
	      return Objects.hash(id);
	  }

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

	public String getControl() {
		return control;
	}

	public void setControl(String control) {
		this.control = control;
	}

	public String getParams() {
		return params;
	}

	public void setParams(String params) {
		this.params = params;
	}

	public Duration getPeriodicity() {
		return periodicity;
	}

	public void setPeriodicity(Duration periodicity) {
		this.periodicity = periodicity;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public int getVersion() {
		return version;
	}

	public void setVersion(int version) {
		this.version = version;
	}
}
