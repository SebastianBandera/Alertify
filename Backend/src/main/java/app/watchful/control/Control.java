package app.watchful.control;

import java.util.Map;

import org.springframework.data.util.Pair;

@FunctionalInterface
public interface Control {
	
	Pair<Map<String, Object>, ControlResultStatus> execute(Map<String, Object> params);
}
