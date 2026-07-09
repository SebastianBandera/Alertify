package app.alertify.control;

import java.util.Map;

@FunctionalInterface
public interface Control {
	
	ControlResponse execute(Map<String, Object> params);
}
