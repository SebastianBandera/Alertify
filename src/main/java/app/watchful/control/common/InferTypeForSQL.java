package app.watchful.control.common;

import java.sql.Types;

public class InferTypeForSQL {

	public int infer(Object object) {
		if(object instanceof Integer) return Types.INTEGER;
		if(object instanceof Double) return Types.DOUBLE;
		if(object instanceof Float) return Types.FLOAT;
		if(object instanceof String) return Types.VARCHAR;
		if(object instanceof java.util.Date) return Types.TIMESTAMP_WITH_TIMEZONE;
		if(object instanceof java.sql.Date) return Types.TIMESTAMP_WITH_TIMEZONE;
		if(object instanceof Boolean) return Types.BOOLEAN;
		
		return Types.NULL;
	}
}
