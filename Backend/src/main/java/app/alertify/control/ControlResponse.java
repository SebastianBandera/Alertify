package app.alertify.control;

import java.util.Map;

public class ControlResponse {

	private final Map<String, Object> data;
    private final ControlResultStatus status;

    public ControlResponse(Map<String, Object> data, boolean success) {
        this.data = data;
        this.status = ControlResultStatus.parse(success);
    }

    public ControlResponse(Map<String, Object> data, ControlResultStatus status) {
        this.data = data;
        this.status = status;
    }

    public Map<String, Object> getData() {
    	return data;
    }
    
    public ControlResultStatus getStatus() {
    	return status;
    }
}
