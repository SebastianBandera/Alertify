package app.watchful.entity;

import java.time.Duration;

import javax.annotation.ManagedBean;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import org.hibernate.annotations.TypeDef;
import org.hibernate.annotations.TypeDefs;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.Data;

@Entity
@Table(schema = "alertas", name = "alertas")
@TypeDefs({
    @TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
})
@Data
@ManagedBean
public class Alerta {

	  @Id
	  @GeneratedValue(strategy=GenerationType.AUTO)
	  private Long id;
	  
	  @Column(name="nombre", nullable = false)
	  private String nombre;

	  @Column(name="control", nullable = false)
	  private String control;

	  @Column(name="parametros")
	  private String parametros;

	  @Column(name="periodicidad")
	  private Duration periodicidad;
}
