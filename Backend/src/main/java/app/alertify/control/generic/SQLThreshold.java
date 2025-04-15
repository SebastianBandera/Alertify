package app.alertify.control.generic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.data.util.Pair;
import org.springframework.jdbc.core.JdbcTemplate;

import app.alertify.control.Control;
import app.alertify.control.ControlResultStatus;
import app.alertify.control.common.InferTypeForSQL;
import app.alertify.control.common.ObjectsUtils;

/**
 * Ejecuta una SQL sobre una base de datos y envia una alerta si se cumple una condición simple:
 * warn_if_bigger, warn_if_lower, warn_if_equal, warn_if_distinct
 */
public class SQLThreshold implements Control {

	private InferTypeForSQL inferTypeForSQL = new InferTypeForSQL();
	
	public SQLThreshold() {
		
	}
	
	@Override
	public Pair<Map<String, Object>, ControlResultStatus> execute(Map<String, Object> params) {
		Objects.requireNonNull(params, "needs args to execute");
		int threshold         = (Integer)params.get(Params.THRESHOLD.getValue());
		String thresholdType  = ObjectsUtils.noNull((String)params.get(Params.THRESHOLD_TYPE.getValue()), "").toLowerCase();
		String descripcion    = ObjectsUtils.noNull((String)params.get(Params.DESCRIPCION.getValue()), "");
		String sql            = ObjectsUtils.noNull((String)params.get(Params.SQL.getValue()), "");
		Object[] paramsSQL    = ObjectsUtils.tryGet(() -> (Object[])params.get(Params.PARAMS_SQL.getValue()), () -> new Object[] {});
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
		result.put(Params.THRESHOLD.toString(), threshold);
		result.put(Params.THRESHOLD_TYPE.toString(), thresholdType);
		result.put(Params.DESCRIPCION.toString(), descripcion);
		
		return Pair.of(result, ControlResultStatus.parse(success));
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

}
