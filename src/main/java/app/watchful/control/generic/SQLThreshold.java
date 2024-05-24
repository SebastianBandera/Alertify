package app.watchful.control.generic;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.data.util.Pair;
import org.springframework.jdbc.core.JdbcTemplate;

import app.watchful.control.Bean;
import app.watchful.control.Control;
import app.watchful.control.ControlResultStatus;
import app.watchful.control.Parameterized;

public class SQLThreshold implements Control, Bean, Parameterized {

	private static String[] PARAM_LABELS = new String[] {Params.THRESHOLD.getValue(), Params.SQL.getValue(), Params.PARAMS_SQL.getValue(), Params.DATA_SOURCE.getValue()};
	
	public SQLThreshold() {
		
	}
	
	@Override
	public String getName() {
		return "sql_threshold";
	}

	@Override
	public String[] getParamLabels() {
		return PARAM_LABELS;
	}
	
	@Override
	public Pair<Map<String, Object>, ControlResultStatus> execute(Map<String, Object> params) {
		Objects.requireNonNull(params, "needs args to execute");
		int threshold         = (Integer)params.get(Params.THRESHOLD.getValue());
		String sql            = (String)params.get(Params.SQL.getValue());
		Object[] paramsSQL    = tryGet(() -> (Object[])params.get(Params.PARAMS_SQL.getValue()), () -> new Object[] {});
		DataSource dataSource = (DataSource)params.get(Params.DATA_SOURCE.getValue());
		
		Map<String, Object> result = new HashMap<>();
		boolean success = false;
		
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		
		int[] types = generate(paramsSQL);
		
		int count = jdbc.query(sql, paramsSQL, types, rs -> rs.next() ? rs.getInt(1) : 0 );
		
		success = count >= threshold;
		
		return Pair.of(result, ControlResultStatus.parse(success));
	}

	private <T> T tryGet(Supplier<T> supplier, Supplier<T> defaultSupplier) {
		try {
			return supplier.get();
		} catch (Exception e) {
			return defaultSupplier.get();
		}
	}

	private int[] generate(Object[] paramsSQL) {
		int[] array = new int[paramsSQL.length];
		
		for (int i = 0; i < array.length; i++) {
			array[i] = infer(paramsSQL[i]);
		}
		
		return array;
	}

	private int infer(Object object) {
		if(object instanceof Integer) return Types.INTEGER;
		if(object instanceof Double) return Types.DOUBLE;
		if(object instanceof Float) return Types.FLOAT;
		if(object instanceof String) return Types.VARCHAR;
		if(object instanceof java.util.Date) return Types.TIMESTAMP_WITH_TIMEZONE;
		if(object instanceof java.sql.Date) return Types.TIMESTAMP_WITH_TIMEZONE;
		if(object instanceof Boolean) return Types.BOOLEAN;
		
		return Types.NULL;
	}
	
	public enum Params {
		THRESHOLD("threshold"),
		PARAMS_SQL("params"),
		SQL("sql"),
		DATA_SOURCE("data_source");

		private String value;
		
		Params(String str) {
			this.value = str;
		}
		
		String getValue() {
			return this.value;
		}
		
		@Override
		public String toString() {
			return this.value;
		}
	}

}
