package app.alertify.controller.dto.summary.v1;

import java.util.List;

import lombok.Data;

@Data
public class CheckGroup {
    
	private String name;
    private List<Check> checks;
}
