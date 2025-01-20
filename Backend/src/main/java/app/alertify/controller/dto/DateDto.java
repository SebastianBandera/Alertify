package app.alertify.controller.dto;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

public class DateDto {
	
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
	private Date date;
	
	public DateDto(Date date) {
		this.date = date;
	}

	public Date getDate() {
		return date;
	}

	public void setDate(Date date) {
		this.date = date;
	}
}
