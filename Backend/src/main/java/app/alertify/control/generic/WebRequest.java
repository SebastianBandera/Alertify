package app.alertify.control.generic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import app.alertify.control.Control;
import app.alertify.control.ControlResultStatus;
import app.alertify.control.common.ObjectsUtils;

/**
 * Dada una URL, método, body y headers(Array donde cada item tiene separacion con :), prueba si el response code es el esperado.
 */
public class WebRequest implements Control {

	private static final Logger log = LoggerFactory.getLogger(WebRequest.class);
    
	public WebRequest() {
		
	}
	
	@Override
	public Pair<Map<String, Object>, ControlResultStatus> execute(Map<String, Object> params) {
		Objects.requireNonNull(params, "needs args to execute");
		String url    = ObjectsUtils.noNull((String)params.get(Params.URL.getValue()), "");
		String method = ObjectsUtils.noNull((String)params.get(Params.METHOD.getValue()), "");
		String body   = (String)params.get(Params.BODY.getValue());
		Object[] headers = ObjectsUtils.tryGet(() -> (Object[])params.get(Params.HEADERS.getValue()), () -> new Object[] {});
		Integer resposeExpected = (Integer)params.get(Params.RESPONSE_CODE_EXPECTED.getValue());
		Object[] regex_check = ObjectsUtils.tryGet(() -> (Object[])params.get(Params.REGEX_EXTRA_CHECK.getValue()), () -> new Object[] {});
		
		Map<String, Object> result = new HashMap<>();
		boolean success = false;
		
		RestTemplate rt = new RestTemplate();
		
		HttpMethod httpMethod = parse(method);
		
		ResponseEntity<String> responseEntity = null;

		try {
			HttpHeaders httpHeaders = new HttpHeaders();
			if (headers != null) {
				for (int i = 0; i < headers.length; i++) {
					try {
						String data = (String)headers[i];
						int index = data.indexOf(":");
						String name = data.substring(0, index).trim();
						String value = data.substring(index+1).trim();
						httpHeaders.add(name, value);
					} catch (Exception e) {
						log.error("error with headers", e);
					}
				}
			}
			
			HttpEntity<String> entity = new HttpEntity<>(body, httpHeaders);
			
			responseEntity = rt.exchange(url, httpMethod, entity, String.class);
		} catch (Exception e) {
			log.error("error with exchange", e);
		}
		
		success = responseEntity != null && responseEntity.getStatusCode().value() == resposeExpected;
		
		if (responseEntity!=null) {
			result.put("statusCode", responseEntity.getStatusCode().value());
		} else {
            result.put("statusCode", -1);
        }
		
		result.put(Params.RESPONSE_CODE_EXPECTED.toString(), resposeExpected);
		
		if(responseEntity != null && regex_check!=null && regex_check.length > 0) {
			String bodyStr = responseEntity.getBody();
			for (int i = 0; i < regex_check.length; i++) {
				String regex = (String)regex_check[i];
				
				Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
				Matcher matcher = pattern.matcher(bodyStr);
				boolean isValid = matcher.find();
				
				result.put("regex_result_isvalid", isValid);
				
				if(!isValid) {
					success = false;
					result.put("regex_result_no_valid_index", i);
					break;
				}
			}
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
		HEADERS("headers"),
		BODY("body"),
		METHOD("method"),
		RESPONSE_CODE_EXPECTED("response_code_expected"),
		REGEX_EXTRA_CHECK("regex_extra_check");

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
