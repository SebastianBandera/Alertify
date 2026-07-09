package app.alertify.control.generic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.alertify.control.Control;
import app.alertify.control.ControlResponse;

/**
 * Prueba que un DataSource sea valido según 'isValid'
 */
public class TestConnection implements Control {

    private static final Logger log = LoggerFactory.getLogger(TestConnection.class);
	
	public TestConnection() {
		
	}
	
	@Override
	public ControlResponse execute(Map<String, Object> params) {
		Objects.requireNonNull(params, "needs args to execute");
		DataSource dataSource = (DataSource)params.get(Params.DATA_SOURCE.getValue());
		Objects.requireNonNull(dataSource, "needs a datasource");
		
		Map<String, Object> result = new HashMap<>();
		boolean success = false;
		
		try {
			if (dataSource.getConnection().isValid(5)) {
				success = true;
			} else {
				success = false;
			}
		} catch (Exception e) {
			success = false;
			result.put(OutputParams.ERROR.toString(), e.getMessage());
			log.error("error testing connection", e);
		}
		
		return new ControlResponse(result, success);
	}
	
	public enum Params {
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
		ERROR("error");

		private String value;
		
		OutputParams(String str) {
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
