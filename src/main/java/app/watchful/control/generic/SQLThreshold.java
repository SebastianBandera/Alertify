package app.watchful.control.generic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.jdbc.core.JdbcTemplate;

import app.watchful.control.Control;
import app.watchful.control.ControlResultStatus;
import app.watchful.service.InferTypeForSQL;

public class SQLThreshold implements Control {

	@Autowired
	private InferTypeForSQL inferTypeForSQL;
	
	public SQLThreshold() {
		
	}
	
	@Override
	public Pair<Map<String, Object>, ControlResultStatus> execute(Map<String, Object> params) {
		Objects.requireNonNull(params, "needs args to execute");
		int threshold         = (Integer)params.get(Params.THRESHOLD.getValue());
		String thresholdType  = noNull((String)params.get(Params.THRESHOLD_TYPE.getValue()), "").toLowerCase();
		String sql            = noNull((String)params.get(Params.SQL.getValue()), "");
		Object[] paramsSQL    = tryGet(() -> (Object[])params.get(Params.PARAMS_SQL.getValue()), () -> new Object[] {});
		DataSource dataSource = (DataSource)params.get(Params.DATA_SOURCE.getValue());
		
		Map<String, Object> result = new HashMap<>();
		boolean success = false;
		
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		
		int[] types = generate(paramsSQL);
		
		int count = jdbc.query(sql, paramsSQL, types, rs -> rs.next() ? rs.getInt(1) : 0 );
		
		if("warn_if_bigger".equals(thresholdType)) {
			success = count <= threshold;
		} else if ("warn_if_lower".equals(thresholdType)) {
			success = count >= threshold;
		} else if ("warn_if_equal".equals(thresholdType)) {
			success = count != threshold;
		} else if ("warn_if_distinct".equals(thresholdType)) {
			success = count == threshold;
		} else {
			throw new RuntimeException("thresholdType: " + thresholdType + " no recognized");
		}

		result.put("count", count);
		
		return Pair.of(result, ControlResultStatus.parse(success));
	}
	
	private <T> T noNull(T value, T defaultValue) {
		return value == null ? defaultValue : value;
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
			array[i] = inferTypeForSQL.infer(paramsSQL[i]);
		}
		
		return array;
	}
	
	public enum Params {
		THRESHOLD("threshold"),
		THRESHOLD_TYPE("threshold_type"),
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
