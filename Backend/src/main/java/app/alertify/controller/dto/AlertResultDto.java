package app.alertify.controller.dto;

import java.util.Date;
import java.util.Objects;

public class AlertResultDto {

	  private Long id;
	  
	  private AlertDto alert;

	  private Date dateIni;
	  
	  private Date dateEnd;

	  private CodStatusDto statusResult;
	  
	  private String result;

	  private boolean needsReview;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}
	
	public AlertDto getAlert() {
		return alert;
	}

	public void setAlert(AlertDto alert) {
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

	public CodStatusDto getStatusResult() {
		return statusResult;
	}

	public void setStatusResult(CodStatusDto statusResult) {
		this.statusResult = statusResult;
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
		AlertResultDto other = (AlertResultDto) obj;
		return Objects.equals(id, other.id);
	}

	@Override
	public String toString() {
		return "AlertResultDto [id=" + id + ", alert=" + alert + ", dateIni=" + dateIni + ", dateEnd=" + dateEnd
				+ ", statusResult=" + statusResult + ", result=" + result + ", needsReview=" + needsReview + "]";
	}
	  
	  
}
