package app.alertify.control.generic;

import java.sql.Types;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRow.Tag;
import com.github.difflib.text.DiffRowGenerator;

import app.alertify.control.Control;
import app.alertify.control.ControlResultStatus;
import app.alertify.control.common.ObjectsUtils;

public class WebWatch implements Control {
	
	private static final Logger log = LoggerFactory.getLogger(WebWatch.class);
	
	private final static String SCHEMA = "webwatch";
	private final static String TABLE = "webwatch_history_v1";
	
	private final JdbcTemplate localJdbc;
	
	public WebWatch(JdbcTemplate localJdbc) {
		Objects.requireNonNull(localJdbc, "needs args to create instance");
		this.localJdbc = localJdbc;
	}
	
	@Override
	public Pair<Map<String, Object>, ControlResultStatus> execute(Map<String, Object> params) {
		Objects.requireNonNull(params, "needs args to execute");
		String url    = ObjectsUtils.noNull((String)params.get(Params.URL.getValue()), "");
		String method = ObjectsUtils.noNull((String)params.get(Params.METHOD.getValue()), "");
		String body   = (String)params.get(Params.BODY.getValue());
		Object[] headers  = ObjectsUtils.tryGet(() -> (Object[])params.get(Params.HEADERS.getValue()), () -> new Object[] {});
		String control_id = ObjectsUtils.noNull((String)params.get(Params.CONTROL_IDENTIFIER.getValue()), "");
		
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
						e.printStackTrace();
					}
				}
			}
			
			HttpEntity<String> entity = new HttpEntity<>(body, httpHeaders);
			
			responseEntity = rt.exchange(url, httpMethod, entity, String.class);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		success = responseEntity != null && responseEntity.getStatusCodeValue() == 200;

		if (responseEntity!=null) {
			result.put("statusCode", responseEntity.getStatusCodeValue());
		} else {
            result.put("statusCode", -1);
        }
		
		if (success) {
			prepare();
			
			String data = responseEntity.getBody();
			data = data == null ? "" : data.trim();
			
			try {
				Map<String, Object> map = localJdbc.queryForMap("select * from " + SCHEMA + "." + TABLE + " "
																+ "where id_name = ? "
																+ "order by date desc "
																+ "limit 1", control_id);
				
				if(map.containsKey("data")) {
					String oldData = (String)map.get("data");
					oldData = oldData == null ? "" : oldData.trim();
					
					return processCompare(result, oldData, data, control_id);
				} else {
					result.put("msg", "Can't find data attribute");
					return Pair.of(result, ControlResultStatus.ERROR);
				}
			} catch (DataAccessException e) {
				int res = localJdbc.update("INSERT INTO " + SCHEMA + "." + TABLE + "(id_name, date, data) VALUES(?, NOW(), ?)", control_id, data);
				if(res != 1) {
					result.put("msg", "Can't insert one single row. Case 1.");
					return Pair.of(result, ControlResultStatus.ERROR);
				} else {
					return Pair.of(result, ControlResultStatus.SUCCESS);
				}
			}
		}
		
		return Pair.of(result, ControlResultStatus.WARN);
	}
	
	private Pair<Map<String, Object>, ControlResultStatus> processCompare(Map<String, Object> result, String oldData, String newData, String control_id) {
		DiffRowGenerator generator = DiffRowGenerator.create()
                .showInlineDiffs(true)
                .mergeOriginalRevised(false)
                .inlineDiffByWord(true)
                .oldTag(f -> "~")      //introduce markdown style for strikethrough
                .newTag(f -> "**")     //introduce markdown style for bold
                .build();
		
		List<DiffRow> rows = generator.generateDiffRows(
                Arrays.asList(oldData.split("\n")),
                Arrays.asList(newData.split("\n")));
		
		Iterator<DiffRow> iter = rows.iterator();
		
		boolean found = false;
		
		StringBuilder sb = new StringBuilder(64);
		
		while (iter.hasNext()) {
			DiffRow diffRow = iter.next();
			
			if (diffRow.getTag() != Tag.EQUAL) {
				found = true;
				
				sb.append(diffRow.toString()).append(";");
			}
		}
		
		if (found) {
			result.put("diff", sb.toString());
			
			int res = localJdbc.update("INSERT INTO " + SCHEMA + "." + TABLE + "(id_name, date, data) VALUES(?, NOW(), ?)", control_id, newData);
			if(res != 1) {
				result.put("msg", "Can't insert one single row. Case 2.");
				return Pair.of(result, ControlResultStatus.ERROR);
			}
		}
		
		return Pair.of(result, ControlResultStatus.parse(!found));
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
	
	private void prepare() {
		boolean schemaExists = schemaExists(SCHEMA);
		
		log.info("schemaExists " + SCHEMA + "?: " + schemaExists);
		
		if (!schemaExists) {
			log.info("CREATING SCHEMA: " + SCHEMA);
			localJdbc.execute("CREATE SCHEMA " + SCHEMA);
		}
		
		boolean tableExists = tableExists(SCHEMA, TABLE);
		
		if (!tableExists) {
			log.info("creating table: " + TABLE);
			
			//could be improved, but saves time
			localJdbc.execute("CREATE TABLE " + SCHEMA + "." + TABLE + " (\r\n"
					+ "	id serial NOT NULL,\r\n"
					+ "	id_name varchar(32) NULL,\r\n"
					+ "	\"date\" timestamp with time zone NULL,\r\n"
					+ "	\"data\" text NULL,\r\n"
					+ "	CONSTRAINT " + TABLE + "_pk PRIMARY KEY (id)\r\n"
					+ ");");
			
			log.info("table created: " + TABLE);
		}
		
		
		
	}

	private boolean tableExists(String schema, String tableName) {
		boolean tableExists = localJdbc.query("SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)", new Object[] {schema, tableName.toLowerCase()}, new int[] {Types.VARCHAR, Types.VARCHAR}, rs -> rs.next() ? rs.getBoolean(1) : false );
		
		return tableExists;
	}
	
	private boolean schemaExists(String schema) {
		int count = localJdbc.query("SELECT count(1) FROM information_schema.schemata WHERE schema_name = ?", new Object[] {SCHEMA}, new int[] {Types.VARCHAR}, rs -> rs.next() ? rs.getInt(1) : 0 );
		
		return count != 0;
	}

	public enum Params {
		URL("url"),
		CONTROL_IDENTIFIER("id"),
		HEADERS("headers"),
		BODY("body"),
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
