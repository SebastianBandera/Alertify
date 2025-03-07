package app.alertify.entity;

import java.util.Date;
import java.util.Objects;

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

@Entity(name = "AlertResult")
@Table(schema = "alert", name = "alerts_result")
@TypeDefs({
    @TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
})
public class AlertResult {

	  @Id
	  @GeneratedValue(strategy=GenerationType.IDENTITY)
	  private Long id;
	  
	  @ManyToOne
	  @JoinColumn(name = "id_alert", nullable = false)
	  private Alert alert;

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
	  private boolean needsReview;
	  
	  @Column(name="active")
	  private boolean active;
	  
	  @Column(name="version")
	  private int version;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Alert getAlert() {
		return alert;
	}

	public void setAlert(Alert alert) {
		this.alert = alert;
	}

	public Date getDateIni() {
		return dateIni;
	}

	public void setDateIni(Date dateIni) {
		this.dateIni = dateIni;
	}

	public Date getDateEnd() {
		return dateEnd;
	}

	public void setDateEnd(Date dateEnd) {
		this.dateEnd = dateEnd;
	}

	public CodStatus getStatusResult() {
		return statusResult;
	}

	public void setStatusResult(CodStatus statusResult) {
		this.statusResult = statusResult;
	}

	public String getParams() {
		return params;
	}

	public void setParams(String params) {
		this.params = params;
	}

	public String getResult() {
		return result;
	}

	public void setResult(String result) {
		this.result = result;
	}

	public boolean isNeedsReview() {
		return needsReview;
	}

	public void setNeedsReview(boolean needsReview) {
		this.needsReview = needsReview;
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

	@Override
	public int hashCode() {
		return Objects.hash(active, alert, dateEnd, dateIni, id, needsReview, params, result, statusResult);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		AlertResult other = (AlertResult) obj;
		return active == other.active && Objects.equals(alert, other.alert) && Objects.equals(dateEnd, other.dateEnd)
				&& Objects.equals(dateIni, other.dateIni) && Objects.equals(id, other.id)
				&& needsReview == other.needsReview && Objects.equals(params, other.params)
				&& Objects.equals(result, other.result) && Objects.equals(statusResult, other.statusResult);
	}

	@Override
	public String toString() {
		return "AlertResult [id=" + id + ", alert=" + alert + ", dateIni=" + dateIni + ", dateEnd=" + dateEnd
				+ ", statusResult=" + statusResult + ", params=" + params + ", result=" + result + ", needsReview="
				+ needsReview + ", active=" + active + "]";
	}
	  
	  
}
