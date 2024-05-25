package app.watchful.entity;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.ManagedBean;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import org.hibernate.annotations.TypeDef;
import org.hibernate.annotations.TypeDefs;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.hypersistence.utils.hibernate.type.interval.PostgreSQLIntervalType;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Entity(name = "Alert")
@Table(schema = "alert", name = "alerts")
@TypeDef(
	    typeClass = PostgreSQLIntervalType.class,
	    defaultForType = Duration.class
	)
@TypeDefs({
    @TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
})
@Data
@Slf4j
@ManagedBean
public class Alert {

	  @Id
	  @GeneratedValue(strategy=GenerationType.IDENTITY)
	  private Long id;
	  
	  @Column(name="name", nullable = false)
	  private String name;

	  @Column(name="control", nullable = false)
	  private String control;

	  @Column(name="params")
	  private String params;

	  @Column(name="periodicity", columnDefinition = "interval")
	  private Duration periodicity;
	  
	  @SuppressWarnings("unchecked")
	  public Map<String,Object> getParametrosMap() {
			try {
				return new ObjectMapper().readValue(params, HashMap.class);
			} catch (Exception e) {
				log.error("error getParametrosMap()", e);
				return new HashMap<>();
			}
	  }
}
