package app.alertify.controller.dto.summary.v1;

import java.time.Duration;
import java.util.Date;
import java.util.List;

import lombok.Data;

@Data
public class Check {

    private String name;
    private Date lastSuccess;
    private Duration period;
    private List<CheckError> errors;
}
