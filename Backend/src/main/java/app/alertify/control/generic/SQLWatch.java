package app.alertify.control.generic;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import app.alertify.control.Control;
import app.alertify.control.ControlResponse;
import app.alertify.control.ControlResultStatus;
import app.alertify.control.common.InferTypeForSQL;
import app.alertify.control.common.ListMerger;
import app.alertify.control.common.ObjectsUtils;
import app.alertify.control.common.StringUtils;

public class SQLWatch implements Control {
    private static final Logger log = LoggerFactory.getLogger(SQLWatch.class);

	private final static String SCHEMA = "sqlwatch";
	
	private InferTypeForSQL inferTypeForSQL = new InferTypeForSQL();
	
	private final JdbcTemplate localJdbc;
	
	public SQLWatch(JdbcTemplate localJdbc) {
		Objects.requireNonNull(localJdbc, "needs args to create instance");
		this.localJdbc = localJdbc;
	}
	
	@Override
	public ControlResponse execute(Map<String, Object> params) {
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
		
		result.put(OutputParams.ROWS.toString(), data.size());

		log.info("firstInvocation: " + firstInvocation);
		
		result.put(OutputParams.FIRST_INVOCATION.toString(), firstInvocation);
		
		if (firstInvocation) {
			if (data.isEmpty()) {
				result.put(OutputParams.ERROR.toString(), "cant empty when creating table");
				new ControlResponse(result, ControlResultStatus.ERROR);
			}
			
			Map<String, Object> firstRow = data.get(0);
			
			int[] typesColumns = generate(firstRow.values().toArray());
			
			createTable(SCHEMA, control_id, firstRow.keySet().toArray(), typesColumns);
			
			loadTable(SCHEMA, control_id, data, types);
			
			success = true;
		} else {
			List<Map<String, Object>> backupData = loadFromBackup(SCHEMA, control_id);
			
			try {
				Map<String, Object> compareResult = compare(backupData, data, keysSQL);
				result.putAll(compareResult);
			} catch (Exception e) {
				log.error("Error", e);
				new ControlResponse(result, ControlResultStatus.ERROR);
			}
			
			if (result.containsKey("repBackupData") || result.containsKey("repNewData") || (int)result.get("newRowsSize")>0 || (int)result.get("remRowsSize")>0 || (int)result.get("modRowsSize")>0) {
				new ControlResponse(result, ControlResultStatus.WARN);
			}
			
			success = true;
		}
		
		return new ControlResponse(result, success);
	}
	
	private Map<String, Object> compare(List<Map<String, Object>> backupData, List<Map<String, Object>> newData, Object[] keysSQL) throws Exception {
		log.info("Comparing data... " + backupData.size() + "x" + newData.size());
		
		Map<String, Object> compareResult = new HashMap<>();
		
		if (backupData.size() != newData.size()) {
			log.info("size mismatch");
			compareResult.put(OutputParams.SIZE_BACKUP.toString(), backupData.size());
			compareResult.put(OutputParams.SIZE_NEW_DATA.toString(), newData.size());
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
        	compareResult.put(OutputParams.REP_BACKUP_DATA.toString(), repbackupData);
		}
        
        if (!repbackupData.isEmpty()) {
        	compareResult.put(OutputParams.REP_NEW_DATA.toString(), repnewData);
		}
        
        if (!repnewData.isEmpty() || !repbackupData.isEmpty()) {
        	return compareResult;
        }

        ListMerger<Map<String, Object>>.MergeResults mergeResults = merger.merge(backupData, newData);
        
        compareResult.put(OutputParams.NEW_ROWS_SIZE.toString(), mergeResults.getNewItems().size());
        compareResult.put(OutputParams.REM_ROWS_SIZE.toString(), mergeResults.getRemovedItems().size());
        compareResult.put(OutputParams.MOD_ROWS_SIZE.toString(), mergeResults.getChangedItems().size());
		
        if (!mergeResults.getNewItems().isEmpty()) {
        	compareResult.put(OutputParams.NEW_ROWS.toString(), mergeResults.getNewItems());
		}
        if (!mergeResults.getRemovedItems().isEmpty()) {
        	compareResult.put(OutputParams.REM_ROWS.toString(), mergeResults.getRemovedItems());
		}
        if (!mergeResults.getChangedItems().isEmpty()) {
        	compareResult.put(OutputParams.MOD_ROWS.toString(), mergeResults.getChangedItems());
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
		Boolean tableExists = localJdbc.query("SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)", new Object[] {schema, tableName.toLowerCase()}, new int[] {Types.VARCHAR, Types.VARCHAR}, rs -> rs.next() ? rs.getBoolean(1) : false );
		
		return tableExists != null && tableExists;
	}
	
	private boolean schemaExists(String schema) {
		Integer count = localJdbc.query("SELECT count(1) FROM information_schema.schemata WHERE schema_name = ?", new Object[] {SCHEMA}, new int[] {Types.VARCHAR}, rs -> rs.next() ? rs.getInt(1) : 0 );
		
		return count != null && count != 0;
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
	
	public enum OutputParams {
		ROWS("rows"),
		FIRST_INVOCATION("firstInvocation"),
		SIZE_BACKUP("sizeBackup"),
		SIZE_NEW_DATA("sizeNewData"),
		REP_BACKUP_DATA("repBackupData"),
		REP_NEW_DATA("repNewData"),
		NEW_ROWS_SIZE("newRowsSize"),
		REM_ROWS_SIZE("remRowsSize"),
		MOD_ROWS_SIZE("modRowsSize"),
		NEW_ROWS("newRows"),
		REM_ROWS("remRows"),
		MOD_ROWS("modRows"),
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
