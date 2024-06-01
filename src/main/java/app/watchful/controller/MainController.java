package app.watchful.controller;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import app.watchful.control.ControlResultStatus;
import app.watchful.control.generic.SQLThreshold;
import app.watchful.control.generic.SQLThreshold.Params;
import app.watchful.databases.DataSourceProperties;
import app.watchful.entity.Alert;

@RestController
public class MainController {

	@Autowired
	private Alert alert;
	
	@Autowired
	private DataSourceProperties dataSourceProperties;
	
	@GetMapping("/")
	public String a() {
		String GET_URL = "http://localhost:8080/testGet";

		  RestTemplate restTemplate = new RestTemplate();
	
		  Map<String, String> params = new HashMap<String, String>();
		  params.put("prop1", "1");
		  params.put("prop2", "value");
	
		  String result = null;
		  
		try {
			result = restTemplate.getForObject(GET_URL, String.class, params);;
		} catch (RestClientException e) {
			e.printStackTrace();
		}
		  
		
		
	    return "OK GET 1 " + alert.toString() + ": " + alert.getId() + " result:" + result;
	}
	
	@GetMapping("/testGet")
	public String testGet() {
	    return "OK 2";
	}
	

	@GetMapping("/test")
	public String test() {
		dataSourceProperties.toString();		
		
		SQLThreshold sqlThreshold = new SQLThreshold();
		
		Map<String, Object> map = new HashMap<>();
		map.put(SQLThreshold.Params.DATA_SOURCE.toString(), new SingleConnectionDataSource("jdbc:postgresql://localhost:54321/postgres", "postgres", "postgres", false));
		map.put(SQLThreshold.Params.PARAMS_SQL.toString(), new Object[] {1, "txt"});
		map.put(SQLThreshold.Params.THRESHOLD_TYPE.toString(), "warn_if_bigger");
		map.put(SQLThreshold.Params.SQL.toString(), "select 80 where 1 = ? and 'txt' = ?");
		map.put(SQLThreshold.Params.THRESHOLD.toString(), 20);
		
		Pair<Map<String, Object>, ControlResultStatus> result = sqlThreshold.execute(map);
		
		return result.getSecond().toString();
		
	    //return infer(1) + " \n " + infer("txt") + " \n " + infer(new Date()) + " \n " + infer(1L) + " \n " + infer(Double.valueOf(1.0));
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
}
