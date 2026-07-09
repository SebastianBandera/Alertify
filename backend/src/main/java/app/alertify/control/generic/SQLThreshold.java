package app.alertify.control.generic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import app.alertify.control.Control;
import app.alertify.control.ControlResponse;
import app.alertify.control.common.InferTypeForSQL;
import app.alertify.control.common.ObjectsUtils;

/**
 * Ejecuta una SQL sobre una base de datos y envia una alerta si se cumple una condición simple:
 * warn_if_bigger, warn_if_lower, warn_if_equal, warn_if_distinct
 */
public class SQLThreshold implements Control {

	private InferTypeForSQL inferTypeForSQL = new InferTypeForSQL();
	
	private final Function<DataSource, JdbcTemplate> jdbcSupplier;
	
	public SQLThreshold() {
		this.jdbcSupplier = JdbcTemplate::new;
	}
	
	// facilitates the tests
	public SQLThreshold(Function<DataSource, JdbcTemplate> jdbcSupplier) {
		this.jdbcSupplier = jdbcSupplier;
	}
	
	@Override
	public ControlResponse execute(Map<String, Object> params) {
		Objects.requireNonNull(params, "needs args to execute");
		int threshold         = (Integer)params.get(Params.THRESHOLD.getValue());
		String thresholdType  = ObjectsUtils.noNull((String)params.get(Params.THRESHOLD_TYPE.getValue()), "").toLowerCase();
		String descripcion    = ObjectsUtils.noNull((String)params.get(Params.DESCRIPCION.getValue()), "");
		String sql            = ObjectsUtils.noNull((String)params.get(Params.SQL.getValue()), "");
		Object[] paramsSQL    = ObjectsUtils.tryGet(() -> (Object[])params.get(Params.PARAMS_SQL.getValue()), () -> new Object[] {});
		DataSource dataSource = (DataSource)params.get(Params.DATA_SOURCE.getValue());
		
		Map<String, Object> result = new HashMap<>();
		boolean success = false;
		
		JdbcTemplate jdbc = this.jdbcSupplier.apply(dataSource);
		
		int[] types = generate(paramsSQL);
		
		Integer countOriginal = jdbc.query(sql, paramsSQL, types, rs -> rs.next() ? rs.getInt(1) : 0 );
		int count = countOriginal == null ? -1 : countOriginal;
		
		success = evaluate(count, threshold, thresholdType);

		result.put(OutputParams.COUNT.toString(), count);
		result.put(OutputParams.THRESHOLD.toString(), threshold);
		result.put(OutputParams.THRESHOLD_TYPE.toString(), thresholdType);
		result.put(OutputParams.DESCRIPCION.toString(), descripcion);
		
		return new ControlResponse(result, success);
	}
	
	private boolean evaluate(int count, int threshold, String thresholdType) {
		if("warn_if_bigger".equals(thresholdType)) {
			return count <= threshold;
		} else if ("warn_if_lower".equals(thresholdType)) {
			return count >= threshold;
		} else if ("warn_if_equal".equals(thresholdType)) {
			return count != threshold;
		} else if ("warn_if_distinct".equals(thresholdType)) {
			return count == threshold;
		} else {
			throw new RuntimeException("thresholdType: " + thresholdType + " no recognized");
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
		DESCRIPCION("descripcion"),
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
	
	public enum OutputParams {
		COUNT("count"),
		THRESHOLD("threshold"),
		THRESHOLD_TYPE("threshold_type"),
		DESCRIPCION("descripcion");

		private String value;
		
		OutputParams(String str) {
			this.value = str;
		}
		
		public String getValue() {
			return this.value;
		}
		
		@Override
		public String toString() {
			return this.value;
		}
	}

}
