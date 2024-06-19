package app.watchful.entity;

import java.util.Date;

import javax.annotation.ManagedBean;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.hibernate.annotations.TypeDefs;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.Data;

@Entity(name = "AlertResult")
@Table(schema = "alert", name = "alerts_result")
@TypeDefs({
    @TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
})
@Data
@ManagedBean
public class AlertResult {

	  @Id
	  @GeneratedValue(strategy=GenerationType.IDENTITY)
	  private Long id;
	  
	  @ManyToOne
	  @JoinColumn(name = "id_alert", nullable = false)
	  private Alert idAlert;

	  @Temporal(TemporalType.TIMESTAMP)
	  @Column(name = "date_ini", nullable = false)
	  private Date dateIni;
	  
	  @Temporal(TemporalType.TIMESTAMP)
	  @Column(name = "date_end")
	  private Date dateEnd;

	  @ManyToOne
	  @JoinColumn(name = "status_result", nullable = false)
	  private CodStatus statusResult;

	  @Type(type = "jsonb")
	  @Column(name="params")
	  private String params;
	  
	  @Type(type = "jsonb")
	  @Column(name="result")
	  private String result;

	  @Column(name="needsReview", nullable = false)
	  private boolean needs_review;
}
