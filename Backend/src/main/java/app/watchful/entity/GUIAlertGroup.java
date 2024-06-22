package app.watchful.entity;

import javax.annotation.ManagedBean;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import lombok.Data;

@Entity(name = "GUIAlertGroup")
@Table(schema = "gui", name = "alert_group")
@Data
@ManagedBean
public class GUIAlertGroup {

	  @Id
	  @GeneratedValue(strategy=GenerationType.IDENTITY)
	  private Long id;

	  @Column(name="name", nullable = false)
	  private String name;
	  
	  @ManyToOne
	  @JoinColumn(name = "id_alert", nullable = false)
	  private Alert idAlert;

	  @Column(name="order")
	  private boolean order;
}
