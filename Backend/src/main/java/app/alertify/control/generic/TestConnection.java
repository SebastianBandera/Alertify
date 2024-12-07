package app.alertify.control.generic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.data.util.Pair;

import app.alertify.control.Control;
import app.alertify.control.ControlResultStatus;

public class TestConnection implements Control {
	
	public TestConnection() {
		
	}
	
	@Override
	public Pair<Map<String, Object>, ControlResultStatus> execute(Map<String, Object> params) {
		Objects.requireNonNull(params, "needs args to execute");
		DataSource dataSource = (DataSource)params.get(Params.DATA_SOURCE.getValue());
		
		Map<String, Object> result = new HashMap<>();
		boolean success = false;
		
		int timeout = 5;
		
		try {
			if (dataSource.getConnection().isValid(timeout)) {
				success = true;
			} else {
				success = false;
				result.put("msg", "Test connection failed (" + timeout + " seconds)");
			}
		} catch (Exception e) {
			success = false;
			result.put("msg", "Error");
			e.printStackTrace();
		}
		
		return Pair.of(result, ControlResultStatus.parse(success));
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

}
