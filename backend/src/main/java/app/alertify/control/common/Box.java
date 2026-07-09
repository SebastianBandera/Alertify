package app.alertify.control.common;

public class Box<T> {

	private T value;
	
	public Box(T value) {
		this.setValue(value);
	}

	public T getValue() {
		return value;
	}

	public void setValue(T value) {
		this.value = value;
	}

	@Override
	public String toString() {
		return "Box [value=" + value + "]";
	}
}