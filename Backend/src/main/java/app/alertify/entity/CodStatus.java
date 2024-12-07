package app.alertify.entity;

import javax.annotation.ManagedBean;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import lombok.Data;

@Entity(name = "CodStatus")
@Table(schema = "alert", name = "cod_status")
@Data
@ManagedBean
public class CodStatus {

	  @Id
	  @GeneratedValue(strategy=GenerationType.IDENTITY)
	  private Long id;
	  
	  @Column(name="name", nullable = false)
	  private String name;
}
