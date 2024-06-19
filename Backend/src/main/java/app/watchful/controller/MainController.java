package app.watchful.controller;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;

import app.watchful.control.ControlResultStatus;
import app.watchful.control.generic.SQLThreshold;
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

	@GetMapping("/testGetWatch")
	public String testGetWatch() {
	    return "Line 1\nLine 2\nLine 3";
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
	}

	@GetMapping("/test2")
	public String test2() {
		DiffRowGenerator generator = DiffRowGenerator.create()
										                .showInlineDiffs(true)
										                .mergeOriginalRevised(true)
										                .inlineDiffByWord(true)
										                .oldTag(f -> "~")      //introduce markdown style for strikethrough
										                .newTag(f -> "**")     //introduce markdown style for bold
										                .build();

		System.out.println("PRUEBA 1");
		
		//compute the differences for two test texts.
		List<DiffRow> rows = generator.generateDiffRows(
		                Arrays.asList("This is a test senctence.\nsecond line.\ntext.\ntext."),
		                Arrays.asList("This is a test for diffutils.\nSEcond line.\ntext."));
		
		for (int i = 0; i < rows.size(); i++) {
			System.out.println(i+": "+rows.get(i).getOldLine());
			System.out.println(i+": "+rows.get(i).getNewLine());
			System.out.println(i+": "+rows.get(i).getTag());
		}
		
		System.out.println();
		System.out.println();

		System.out.println("PRUEBA 2");
		
		List<DiffRow> rows2 = generator.generateDiffRows(
                Arrays.asList("This is a test senctence.", "This is the second line.", "And here is the finish."),
                Arrays.asList("This is a test for diffutils.", "This is the second line."));
		
		
		for (int i = 0; i < rows2.size(); i++) {
			System.out.println(i+": "+rows2.get(i).getOldLine());
			System.out.println(i+": "+rows2.get(i).getNewLine());
			System.out.println(i+": "+rows2.get(i).getTag());
		}
		
		System.out.println();
		System.out.println();

		System.out.println("PRUEBA 3");
		
		String text1 = "This is a test senctence.\nline!!.\ntext.\ntext.\nanother text.";
		String text2 = "This is a test for diffutils.\nline!!.\ntext.\nanother text.";
		
		List<DiffRow> rows3 = generator.generateDiffRows(
                Arrays.asList(text1.split("\n")),
                Arrays.asList(text2.split("\n")));
		
		
		for (int i = 0; i < rows3.size(); i++) {
			System.out.println(i+": "+rows3.get(i).getOldLine());
			System.out.println(i+": "+rows3.get(i).getNewLine());
			System.out.println(i+": "+rows3.get(i).getTag());
		}
		
		
		return "1";
	}
	
}
