package app.alertify.controller.dto.summary.v1;

import java.util.Date;

import lombok.Data;

@Data
public class CheckError {

    private Date time;
    private String message;
    private String status;
}
