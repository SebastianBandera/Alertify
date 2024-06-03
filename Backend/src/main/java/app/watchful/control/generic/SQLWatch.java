package app.watchful.control.generic;

import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.data.util.Pair;
import org.springframework.jdbc.core.JdbcTemplate;

import app.watchful.control.Control;
import app.watchful.control.ControlResultStatus;
import app.watchful.control.common.InferTypeForSQL;
import app.watchful.control.common.ListMerger;
import app.watchful.control.common.ObjectsUtils;
import app.watchful.control.common.StringUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SQLWatch implements Control {

	private final static String SCHEMA = "sqlwatch";
	
	private InferTypeForSQL inferTypeForSQL = new InferTypeForSQL();
	
	private final JdbcTemplate localJdbc;
	
	public SQLWatch(JdbcTemplate localJdbc) {
		Objects.requireNonNull(localJdbc, "needs args to create instance");
		this.localJdbc = localJdbc;
	}
	
	@Override
	public Pair<Map<String, Object>, ControlResultStatus> execute(Map<String, Object> params) {
		Objects.requireNonNull(params, "needs args to execute");
		String sql            = ObjectsUtils.noNull((String)params.get(Params.SQL.getValue()), "");
		String control_id     = ObjectsUtils.noNull((String)params.get(Params.CONTROL_IDENTIFIER.getValue()), "");
		Object[] paramsSQL    = ObjectsUtils.tryGet(() -> (Object[])params.get(Params.PARAMS_SQL.getValue()), () -> new Object[] {});
		Object[] keysSQL      = ObjectsUtils.tryGet(() -> (Object[])params.get(Params.KEYS.getValue()), () -> new Object[] {});
		DataSource dataSource = (DataSource)params.get(Params.DATA_SOURCE.getValue());
		
		log.info("control_id: " + control_id);
		
		Map<String, Object> result = new HashMap<>();
		boolean success = false;
		
		boolean firstInvocation = firstInvocation(control_id);
		
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		
		int[] types = generate(paramsSQL);
		
		List<Map<String, Object>> data = jdbc.queryForList(sql + " order by 1", paramsSQL, types);
		
		result.put("rows", data.size());

		log.info("firstInvocation: " + firstInvocation);
		
		if (firstInvocation) {
			if (data.isEmpty()) {
				result.put("error", "cant empty when creating table");
				return Pair.of(result, ControlResultStatus.ERROR);
			}
			
			Map<String, Object> firstRow = data.get(0);
			
			int[] typesColumns = generate(firstRow.values().toArray());
			
			createTable(SCHEMA, control_id, firstRow.keySet().toArray(), typesColumns);
			
			loadTable(SCHEMA, control_id, data, types);
			
			result.put("firstInvocation", firstInvocation);
			
			success = true;
		} else {
			List<Map<String, Object>> backupData = loadFromBackup(SCHEMA, control_id);
			
			try {
				Map<String, Object> compareResult = compare(backupData, data, keysSQL);
				result.putAll(compareResult);
			} catch (Exception e) {
				e.printStackTrace();
				return Pair.of(result, ControlResultStatus.ERROR);
			}
			
			if (result.containsKey("repBackupData") || result.containsKey("repNewData") || (int)result.get("newRowsSize")>0 || (int)result.get("remRowsSize")>0 || (int)result.get("modRowsSize")>0) {
				return Pair.of(result, ControlResultStatus.WARN);
			}
			
			success = true;
		}
		
		return Pair.of(result, ControlResultStatus.parse(success));
	}
	
	//Ver en herramientas ETL SDP si ya está esto hecho
	private Map<String, Object> compare(List<Map<String, Object>> backupData, List<Map<String, Object>> newData, Object[] keysSQL) throws Exception {
		log.info("Comparing data... " + backupData.size() + "x" + newData.size());
		
		Map<String, Object> compareResult = new HashMap<>();
		
		if (backupData.size() != newData.size()) {
			log.info("size mismatch");
			compareResult.put("sizeBackup", backupData.size());
			compareResult.put("sizeNewData", newData.size());
		}
		
		Set<String> names = new HashSet<>(backupData.get(0).keySet());
		
		names.removeAll(Arrays.asList(keysSQL).stream().map(item -> (String)item).collect(Collectors.toList()));
		
		String[] values = names.toArray(new String[0]);
		
		List<Function<Map<String, Object>, Object>> keyExtractors = ListMerger.getExtractorsFromMap(Arrays.asList(keysSQL).toArray(new String[0]));
        List<Function<Map<String, Object>, Object>> valueExtractors = ListMerger.getExtractorsFromMap(values);
		
		ListMerger<Map<String, Object>> merger = new ListMerger<>(keyExtractors, valueExtractors);
        
        merger.setFunctionCaseChanged(ListMerger.getFunctionChangedForStringObjectMap("_OLD"));
        
        List<List<Map<String, Object>>> repbackupData = merger.getDuplicatedItems(backupData);
        List<List<Map<String, Object>>> repnewData = merger.getDuplicatedItems(newData);
        
        if (!repnewData.isEmpty()) {
        	compareResult.put("repBackupData", repbackupData);
		}
        
        if (!repbackupData.isEmpty()) {
        	compareResult.put("repNewData", repnewData);
		}
        
        if (!repnewData.isEmpty() || !repbackupData.isEmpty()) {
        	return compareResult;
        }

        ListMerger<Map<String, Object>>.MergeResults mergeResults = merger.merge(backupData, newData);
        
        compareResult.put("newRowsSize", mergeResults.getNewItems().size());
        compareResult.put("remRowsSize", mergeResults.getRemovedItems().size());
        compareResult.put("modRowsSize", mergeResults.getChangedItems().size());
		
        if (!mergeResults.getNewItems().isEmpty()) {
        	compareResult.put("newRows", mergeResults.getNewItems());
		}
        if (!mergeResults.getRemovedItems().isEmpty()) {
        	compareResult.put("remRows", mergeResults.getRemovedItems());
		}
        if (!mergeResults.getChangedItems().isEmpty()) {
        	compareResult.put("modRows", mergeResults.getChangedItems());
		}
        
		log.info("Comparing data ends");
		
		return compareResult;
	}
	
	private List<Map<String, Object>> loadFromBackup(String schema, String control_id) {
		return localJdbc.queryForList(StringUtils.concat("SELECT * FROM ", schema, ".", control_id, " order by 1"));
	}

	private void loadTable(String schema, String control_id, List<Map<String, Object>> data, int[] types) {
		String placeholders = String.join(",", Collections.nCopies(data.get(0).keySet().size(), "?").stream().collect(Collectors.toList()));
		
		String sql = StringUtils.concat("INSERT INTO ", schema, ".", control_id, " VALUES(", placeholders, ");");
		
		List<Object[]> batchArgs = data.stream().map(map -> map.values().toArray()).collect(Collectors.toList());
		
		log.info("loading data into table");
		
		int[] result = localJdbc.batchUpdate(sql, batchArgs, types);
		
		log.info("result len: " + result.length + (result.length > 0 ? ", peek first element " + result[0] : ""));
	}

	private void createTable(String schema, String control_id, Object[] names, int[] typesColumns) {
		String[] types = parse(typesColumns);
		
		if (names.length != typesColumns.length) {
			throw new RuntimeException("arrays length mismatch");
		}
		
		String[] stringBuilt = new String[typesColumns.length];
		
		for (int i = 0; i < typesColumns.length; i++) {
			String name = names[i].toString();
			String type = types[i];
			
			stringBuilt[i] = StringUtils.concat(name, " ", type, " NULL");
		}
		
		String coreValues = String.join(",", stringBuilt);
		
		String sql = StringUtils.concat("CREATE TABLE ", schema, ".", control_id, "(", coreValues, ");");
		
		log.info(StringUtils.concat("creating table ",  SCHEMA, ".",  control_id));
		
		localJdbc.execute(sql);
	}
	
	private String[] parse(int[] typesColumns) {
		String[] result = new String[typesColumns.length];
		for (int i = 0; i < typesColumns.length; i++) {
			result[i] = inferTypeForSQL.toTableType(typesColumns[i]);
		}
		return result;
	}

	private boolean firstInvocation(String control_id) {
		boolean schemaExists = schemaExists(SCHEMA);
		
		log.info("schemaExists " + SCHEMA + "?: " + schemaExists);
		
		if (!schemaExists) {
			log.info("CREATING SCHEMA: " + SCHEMA);
			localJdbc.execute("CREATE SCHEMA " + SCHEMA);
		}
		
		boolean tableExists = tableExists(SCHEMA, control_id);
		
		log.info("tableExists " + control_id + "?: " + tableExists);
		
		return !tableExists;
	}

	private boolean tableExists(String schema, String tableName) {
		boolean tableExists = localJdbc.query("SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)", new Object[] {schema, tableName.toLowerCase()}, new int[] {Types.VARCHAR, Types.VARCHAR}, rs -> rs.next() ? rs.getBoolean(1) : false );
		
		return tableExists;
	}
	
	private boolean schemaExists(String schema) {
		int count = localJdbc.query("SELECT count(1) FROM information_schema.schemata WHERE schema_name = ?", new Object[] {SCHEMA}, new int[] {Types.VARCHAR}, rs -> rs.next() ? rs.getInt(1) : 0 );
		
		return count != 0;
	}

	private int[] generate(Object[] paramsSQL) {
		int[] array = new int[paramsSQL.length];
		
		for (int i = 0; i < array.length; i++) {
			array[i] = inferTypeForSQL.infer(paramsSQL[i]);
		}
		
		return array;
	}
	
	public enum Params {
		SQL("sql"),
		CONTROL_IDENTIFIER("id"),
		KEYS("keys"),
		PARAMS_SQL("params"),
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
