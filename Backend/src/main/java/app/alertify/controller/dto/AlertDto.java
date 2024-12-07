package app.alertify.controller.dto;

import java.time.Duration;

import lombok.Data;

@Data
public class AlertDto {

	private Long id;
	  
	private String name;

	private Duration periodicity;
	  
	  
}
