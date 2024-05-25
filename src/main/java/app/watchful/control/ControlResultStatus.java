package app.watchful.control;

public enum ControlResultStatus {

	SUCCESS("success"),
	WARN("warn"),
	ERROR("error");
	
	private final String value;
	
	ControlResultStatus(String value) {
		this.value = value;
	}
	
	public String getValue() {
		return this.value;
	}
	
	@Override
	public String toString() {
		return this.value;
	}

	public static ControlResultStatus parse(boolean successStatus) {
		return successStatus ? SUCCESS : WARN;
	}
}
