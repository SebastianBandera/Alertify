package app.watchful.controller.dto;

import java.util.Date;

import lombok.Data;

@Data
public class AlertResultDto {

	  private Long id;
	  
	  private AlertDto idAlert;

	  private Date dateIni;
	  
	  private Date dateEnd;

	  private CodStatusDto statusResult;
	  
	  private String result;

	  private boolean needsReview;
}
