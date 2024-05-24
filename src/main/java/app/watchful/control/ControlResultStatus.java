package app.watchful.control;

public enum ControlResultStatus {

	SUCCESS,
	WARN,
	ERROR;
	
	public static ControlResultStatus parse(boolean successStatus) {
		return successStatus ? SUCCESS : WARN;
	}
}
