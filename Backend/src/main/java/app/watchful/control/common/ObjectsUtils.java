package app.watchful.control.common;

import java.util.function.Supplier;

public class ObjectsUtils {

	public static <T> T noNull(T value, T defaultValue) {
		return value == null ? defaultValue : value;
	}

	public static <T> T tryGet(Supplier<T> supplier, Supplier<T> defaultSupplier) {
		try {
			return supplier.get();
		} catch (Exception e) {
			return defaultSupplier.get();
		}
	}
}
