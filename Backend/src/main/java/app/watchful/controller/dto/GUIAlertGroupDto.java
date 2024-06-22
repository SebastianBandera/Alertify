package app.watchful.controller.dto;

import lombok.Data;

@Data
public class GUIAlertGroupDto {

	  private Long id;

	  private String name;
	  
	  private AlertDto idAlert;

	  private boolean order;
	  
	  
}
