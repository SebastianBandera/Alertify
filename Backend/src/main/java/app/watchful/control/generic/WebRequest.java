package app.watchful.control.generic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.util.Pair;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import app.watchful.control.Control;
import app.watchful.control.ControlResultStatus;
import app.watchful.control.common.ObjectsUtils;

public class WebRequest implements Control {
	
	public WebRequest() {
		
	}
	
	@Override
	public Pair<Map<String, Object>, ControlResultStatus> execute(Map<String, Object> params) {
		Objects.requireNonNull(params, "needs args to execute");
		String url    = ObjectsUtils.noNull((String)params.get(Params.URL.getValue()), "").toLowerCase();
		String method = ObjectsUtils.noNull((String)params.get(Params.METHOD.getValue()), "").toLowerCase();
		
		Map<String, Object> result = new HashMap<>();
		boolean success = false;
		
		RestTemplate rt = new RestTemplate();
		
		HttpMethod httpMethod = parse(method);
		
		ResponseEntity<String> responseEntity = null;
		
		try {
			responseEntity = rt.exchange(url, httpMethod, null, String.class);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		success = responseEntity != null && responseEntity.getStatusCodeValue() == 200;
		
		if (responseEntity!=null) {
			result.put("statusCode", responseEntity.getStatusCodeValue());
		}
		
		return Pair.of(result, ControlResultStatus.parse(success));
	}
	
	private HttpMethod parse(String method) {
		if(method == null) return HttpMethod.GET;
		
		method = method.toUpperCase();
		
		HttpMethod result = null;
		
		switch (method) {
		case "GET":
			result = HttpMethod.GET;
			break;
		case "POST":
			result = HttpMethod.POST;
			break;
		case "PATCH":
			result = HttpMethod.PATCH;
			break;
		case "HEAD":
			result = HttpMethod.HEAD;
			break;
		case "PUT":
			result = HttpMethod.PUT;
			break;
		case "OPTIONS":
			result = HttpMethod.OPTIONS;
			break;
		case "TRACE":
			result = HttpMethod.TRACE;
			break;
		case "DELETE":
			result = HttpMethod.DELETE;
			break;
		default:
			result = HttpMethod.GET;
		}
		
		return result;
	}

	public enum Params {
		URL("url"),
		METHOD("method");

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
