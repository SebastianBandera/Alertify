package app.alertify.controller.dto.summary.v1;

import java.util.Date;
import java.util.Objects;

public class CheckError {

    private Date time;
    private String message;
    private String status;
	public Date getTime() {
		return time;
	}
	public void setTime(Date time) {
		this.time = time;
	}
	public String getMessage() {
		return message;
	}
	public void setMessage(String message) {
		this.message = message;
	}
	public String getStatus() {
		return status;
	}
	public void setStatus(String status) {
		this.status = status;
	}
	@Override
	public int hashCode() {
		return Objects.hash(message, status, time);
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CheckError other = (CheckError) obj;
		return Objects.equals(message, other.message) && Objects.equals(status, other.status)
				&& Objects.equals(time, other.time);
	}
	@Override
	public String toString() {
		return "CheckError [time=" + time + ", message=" + message + ", status=" + status + "]";
	}
    
    
}
