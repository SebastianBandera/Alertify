package app.alertify.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class Global {

	public static Charset getAppCharset() {
		return StandardCharsets.UTF_8;
	}
}
