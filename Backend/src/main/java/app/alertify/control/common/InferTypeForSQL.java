package app.alertify.control.common;

import java.sql.Types;

public class InferTypeForSQL {

	public int infer(Object object) {
		if(object instanceof Integer) return Types.INTEGER;
		if(object instanceof Double) return Types.DOUBLE;
		if(object instanceof Float) return Types.FLOAT;
		if(object instanceof Short) return Types.INTEGER;
		if(object instanceof String) return Types.VARCHAR;
		if(object instanceof java.util.Date) return Types.TIMESTAMP_WITH_TIMEZONE;
		if(object instanceof java.sql.Date) return Types.TIMESTAMP_WITH_TIMEZONE;
		if(object instanceof Boolean) return Types.BOOLEAN;
		
		return Types.NULL;
	}
	
	public String toTableType(int type) {
		switch (type) {
		case Types.INTEGER: return "int";
		case Types.DOUBLE: return "double precision";
		case Types.FLOAT: return "float4";
		case Types.VARCHAR: return "varchar";
		case Types.TIMESTAMP_WITH_TIMEZONE: return "time without time zone";
		case Types.BOOLEAN: return "bool";
		}
		
		return "null";
	}
}
