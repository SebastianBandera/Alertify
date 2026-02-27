package test.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.control.ControlResponse;
import app.alertify.control.generic.SQLThreshold;
import app.alertify.control.generic.SQLWatch;
import app.alertify.control.generic.SQLWatch.Params;

@ExtendWith(MockitoExtension.class)
public class SQLWatchTest {
	
	@Mock
    private DataSource dataSource;

	@Mock
    private JdbcTemplate jdbcLocal;
	
	@Mock
    private JdbcTemplate jdbcTemplate;
    
    @InjectMocks
    private SQLWatch sqlWatch;
    
    private String SCHEMA;
	private String SQL_CREATE_SCHEMA;
	private String SQL_SCHEMA_EXISTS;
	private String SQL_TABLE_EXISTS;
	
	private final static int SCHEMA_NOT_EXISTS = 0;
	private final static int SCHEMA_EXISTS = 1;
	private final static boolean TABLE_NOT_EXISTS = false;
	private final static boolean TABLE_EXISTS = true;
	
    @BeforeEach
    void setup() {
        Function<DataSource, JdbcTemplate> supplier = _ -> jdbcTemplate;
        sqlWatch = new SQLWatch(jdbcLocal, supplier);
        
        SCHEMA = (String)ReflectionTestUtils.getField(SQLWatch.class, "SCHEMA");
        SQL_CREATE_SCHEMA = (String)ReflectionTestUtils.getField(SQLWatch.class, "SQL_CREATE_SCHEMA");
        SQL_SCHEMA_EXISTS = (String)ReflectionTestUtils.getField(SQLWatch.class, "SQL_SCHEMA_EXISTS");
        SQL_TABLE_EXISTS = (String)ReflectionTestUtils.getField(SQLWatch.class, "SQL_TABLE_EXISTS");
    }
    
    @Test
	@DisplayName("Falla por error en parametros - null")
    void fallaParametrosNull() {
		assertThrows(Exception.class, () -> sqlWatch.execute(null), "Se esperaba una excepción por no tener los parámetros correctos");
    }

    @Test
	@DisplayName("Falla por error en parametros - empty")
    void fallaParametrosEmpty() {
    	Map<String, Object> params = new HashMap<String, Object>();
		assertThrows(Exception.class, () -> sqlWatch.execute(params), "Se esperaba una excepción por no tener los parámetros correctos");
    }

    @Test
	@DisplayName("CREATE SCHEMA - CREATE TABLE - no data")
    void case1() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_NOT_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
        
    	Mockito.doAnswer(invocation -> {
    	    RowCallbackHandler handler = invocation.getArgument(3);

    	    ResultSet rs = Mockito.mock(ResultSet.class);
    	    ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);

    	    Mockito.when(rs.getMetaData()).thenReturn(meta);
    	    Mockito.when(meta.getColumnCount()).thenReturn(2);
    	    Mockito.when(meta.getColumnName(1)).thenReturn("col1");
    	    Mockito.when(meta.getColumnName(2)).thenReturn("col2");
    	    Mockito.when(meta.getColumnType(1)).thenReturn(Types.VARCHAR);
    	    Mockito.when(meta.getColumnType(2)).thenReturn(Types.INTEGER);

    	    handler.processRow(rs);

    	    return null;
    	}).when(jdbcTemplate).query(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.any(RowCallbackHandler.class)
    	);

    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.times(1)).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcLocal, Mockito.times(1)).execute(
    			Mockito.startsWith("CREATE TABLE " + SCHEMA + ".")
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    
    	assertTrue(response.getStatus().isSuccess());
    	assertEquals(response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()), true);
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }

    @Test
	@DisplayName("CREATE SCHEMA - CREATE TABLE - with data")
    void case2() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 2));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_NOT_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
        
    	Mockito.doAnswer(invocation -> {
    	    RowCallbackHandler handler = invocation.getArgument(3);

    	    ResultSet rs = Mockito.mock(ResultSet.class);
    	    ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);

    	    Mockito.when(rs.getMetaData()).thenReturn(meta);
    	    Mockito.when(meta.getColumnCount()).thenReturn(2);
    	    Mockito.when(meta.getColumnName(1)).thenReturn("col1");
    	    Mockito.when(meta.getColumnName(2)).thenReturn("col2");
    	    Mockito.when(meta.getColumnType(1)).thenReturn(Types.VARCHAR);
    	    Mockito.when(meta.getColumnType(2)).thenReturn(Types.INTEGER);

    	    handler.processRow(rs);

    	    return null;
    	}).when(jdbcTemplate).query(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.any(RowCallbackHandler.class)
    	);

    	Mockito.when(jdbcLocal.batchUpdate(
    	        Mockito.startsWith("INSERT INTO "),
    	        ArgumentMatchers.<List<Object[]>>any(),
    	        Mockito.any(int[].class)
    	)).thenReturn(new int[] {1, 1});
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.times(1)).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcLocal, Mockito.times(1)).execute(
    			Mockito.startsWith("CREATE TABLE " + SCHEMA + ".")
    	);
    	
    	Mockito.verify(jdbcLocal, Mockito.times(1)).batchUpdate(
    	        Mockito.startsWith("INSERT INTO "),
    	        ArgumentMatchers.<List<Object[]>>any(),
    	        Mockito.any(int[].class)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isSuccess());
    	assertEquals(response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()), true);
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }

    @Test
	@DisplayName("CREATE SCHEMA - CREATE TABLE - with data with params")
    void case3() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {"param1", 1234, (double)1, (short)1, (float)1, true, new java.util.Date(), new java.sql.Date(0)};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1, "col3", (double)1, "col4", (short)1, "col5", (float)1, "col6", new java.util.Date(), "col7", new java.sql.Date(0), "col8", false));
    	dataReturn.add(Map.of("col1", "val2", "col2", 2, "col3", (double)1, "col4", (short)1, "col5", (float)1, "col6", new java.util.Date(), "col7", new java.sql.Date(0), "col8", false));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_NOT_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
        
    	Mockito.doAnswer(invocation -> {
    	    RowCallbackHandler handler = invocation.getArgument(3);

    	    ResultSet rs = Mockito.mock(ResultSet.class);
    	    ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);

    	    Mockito.when(rs.getMetaData()).thenReturn(meta);
    	    Mockito.when(meta.getColumnCount()).thenReturn(8);
    	    Mockito.when(meta.getColumnName(1)).thenReturn("col1");
    	    Mockito.when(meta.getColumnName(2)).thenReturn("col2");
    	    Mockito.when(meta.getColumnName(3)).thenReturn("col3");
    	    Mockito.when(meta.getColumnName(4)).thenReturn("col4");
    	    Mockito.when(meta.getColumnName(5)).thenReturn("col5");
    	    Mockito.when(meta.getColumnName(6)).thenReturn("col6");
    	    Mockito.when(meta.getColumnName(7)).thenReturn("col7");
    	    Mockito.when(meta.getColumnName(8)).thenReturn("col8");
    	    Mockito.when(meta.getColumnType(1)).thenReturn(Types.VARCHAR);
    	    Mockito.when(meta.getColumnType(2)).thenReturn(Types.INTEGER);
    	    Mockito.when(meta.getColumnType(3)).thenReturn(Types.DOUBLE);
    	    Mockito.when(meta.getColumnType(4)).thenReturn(Types.DOUBLE);
    	    Mockito.when(meta.getColumnType(5)).thenReturn(Types.FLOAT);
    	    Mockito.when(meta.getColumnType(6)).thenReturn(Types.TIMESTAMP_WITH_TIMEZONE);
    	    Mockito.when(meta.getColumnType(7)).thenReturn(Types.TIMESTAMP_WITH_TIMEZONE);
    	    Mockito.when(meta.getColumnType(8)).thenReturn(Types.BOOLEAN);

    	    handler.processRow(rs);

    	    return null;
    	}).when(jdbcTemplate).query(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.any(RowCallbackHandler.class)
    	);

    	Mockito.when(jdbcLocal.batchUpdate(
    	        Mockito.startsWith("INSERT INTO "),
    	        ArgumentMatchers.<List<Object[]>>any(),
    	        Mockito.any(int[].class)
    	)).thenReturn(new int[] {1, 1});
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.times(1)).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcLocal, Mockito.times(1)).execute(
    			Mockito.startsWith("CREATE TABLE " + SCHEMA + ".")
    	);
    	
    	Mockito.verify(jdbcLocal, Mockito.times(1)).batchUpdate(
    	        Mockito.startsWith("INSERT INTO "),
    	        ArgumentMatchers.<List<Object[]>>any(),
    	        Mockito.any(int[].class)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {Types.VARCHAR, Types.INTEGER, Types.DOUBLE, Types.INTEGER, Types.FLOAT, Types.BOOLEAN, Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE})
    	);
    	
    	assertTrue(response.getStatus().isSuccess());
    	assertEquals(response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()), true);
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }

    @Test
	@DisplayName("SKIP SCHEMA - CREATE TABLE - with data")
    void case4() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 2));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
        
    	Mockito.doAnswer(invocation -> {
    	    RowCallbackHandler handler = invocation.getArgument(3);

    	    ResultSet rs = Mockito.mock(ResultSet.class);
    	    ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);

    	    Mockito.when(rs.getMetaData()).thenReturn(meta);
    	    Mockito.when(meta.getColumnCount()).thenReturn(2);
    	    Mockito.when(meta.getColumnName(1)).thenReturn("col1");
    	    Mockito.when(meta.getColumnName(2)).thenReturn("col2");
    	    Mockito.when(meta.getColumnType(1)).thenReturn(Types.VARCHAR);
    	    Mockito.when(meta.getColumnType(2)).thenReturn(Types.INTEGER);

    	    handler.processRow(rs);

    	    return null;
    	}).when(jdbcTemplate).query(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.any(RowCallbackHandler.class)
    	);
    	
    	Mockito.when(jdbcLocal.query(
		        Mockito.eq(SQL_TABLE_EXISTS),
		        Mockito.eq(new Object[] {SCHEMA, controlId.toLowerCase()}),
		        Mockito.eq(new int[] {Types.VARCHAR, Types.VARCHAR}),
		        Mockito.<ResultSetExtractor<Boolean>>any()
		)).thenReturn(TABLE_NOT_EXISTS);

    	Mockito.when(jdbcLocal.batchUpdate(
    	        Mockito.startsWith("INSERT INTO "),
    	        ArgumentMatchers.<List<Object[]>>any(),
    	        Mockito.any(int[].class)
    	)).thenReturn(new int[] {1, 1});
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.never()).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcLocal, Mockito.times(1)).execute(
    			Mockito.startsWith("CREATE TABLE " + SCHEMA + ".")
    	);
    	
    	Mockito.verify(jdbcLocal, Mockito.times(1)).batchUpdate(
    	        Mockito.startsWith("INSERT INTO "),
    	        ArgumentMatchers.<List<Object[]>>any(),
    	        Mockito.any(int[].class)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isSuccess());
    	assertEquals(response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()), true);
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    @Test
	@DisplayName("SKIP SCHEMA - TABLE ALREADY PRESENT - with data equal")
    void case5() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 2));
    	List<Map<String, Object>> dataBD = new LinkedList<>();
    	dataBD.add(Map.of("col1", "val1", "col2", 1));
    	dataBD.add(Map.of("col1", "val2", "col2", 2));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.LOG_ROWS.toString(), false);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
    	
    	Mockito.when(jdbcLocal.query(
		        Mockito.eq(SQL_TABLE_EXISTS),
		        Mockito.eq(new Object[] {SCHEMA, controlId.toLowerCase()}),
		        Mockito.eq(new int[] {Types.VARCHAR, Types.VARCHAR}),
		        Mockito.<ResultSetExtractor<Boolean>>any()
		)).thenReturn(TABLE_EXISTS);

    	Mockito.when(jdbcLocal.queryForList(
    	        Mockito.startsWith("SELECT * FROM " + SCHEMA)
    	)).thenReturn(dataBD);
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.never()).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isSuccess());
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.REM_ROWS_SIZE.toString()));
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.NEW_ROWS_SIZE.toString()));
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.MOD_ROWS_SIZE.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.ROWS.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()));
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    @Test
	@DisplayName("SKIP SCHEMA - TABLE ALREADY PRESENT - with new data - with logRows")
    void case6() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 2));
    	dataReturn.add(Map.of("col1", "val3", "col2", 3));
    	List<Map<String, Object>> dataBD = new LinkedList<>();
    	dataBD.add(Map.of("col1", "val1", "col2", 1));
    	dataBD.add(Map.of("col1", "val2", "col2", 2));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.LOG_ROWS.toString(), true);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
    	
    	Mockito.when(jdbcLocal.query(
		        Mockito.eq(SQL_TABLE_EXISTS),
		        Mockito.eq(new Object[] {SCHEMA, controlId.toLowerCase()}),
		        Mockito.eq(new int[] {Types.VARCHAR, Types.VARCHAR}),
		        Mockito.<ResultSetExtractor<Boolean>>any()
		)).thenReturn(TABLE_EXISTS);

    	Mockito.when(jdbcLocal.queryForList(
    	        Mockito.startsWith("SELECT * FROM " + SCHEMA)
    	)).thenReturn(dataBD);
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.never()).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isWarn());
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.REM_ROWS_SIZE.toString()));
    	assertEquals(1, response.getData().get(SQLWatch.OutputParams.NEW_ROWS_SIZE.toString()));
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.MOD_ROWS_SIZE.toString()));
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.ROWS.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.SIZE_BACKUP.toString()));
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.SIZE_NEW_DATA.toString()));
    	assertEquals(true, response.getData().get(SQLWatch.OutputParams.LOG_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.MOD_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.REM_ROWS.toString()));
    	assertEquals(new LinkedList<Map<String, Object>>(List.of(dataReturn.get(dataReturn.size() - 1))), response.getData().get(SQLWatch.OutputParams.NEW_ROWS.toString()));
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    @Test
	@DisplayName("SKIP SCHEMA - TABLE ALREADY PRESENT - with new data - with no logRows")
    void case7() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 2));
    	dataReturn.add(Map.of("col1", "val3", "col2", 3));
    	List<Map<String, Object>> dataBD = new LinkedList<>();
    	dataBD.add(Map.of("col1", "val1", "col2", 1));
    	dataBD.add(Map.of("col1", "val2", "col2", 2));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.LOG_ROWS.toString(), false);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
    	
    	Mockito.when(jdbcLocal.query(
		        Mockito.eq(SQL_TABLE_EXISTS),
		        Mockito.eq(new Object[] {SCHEMA, controlId.toLowerCase()}),
		        Mockito.eq(new int[] {Types.VARCHAR, Types.VARCHAR}),
		        Mockito.<ResultSetExtractor<Boolean>>any()
		)).thenReturn(TABLE_EXISTS);

    	Mockito.when(jdbcLocal.queryForList(
    	        Mockito.startsWith("SELECT * FROM " + SCHEMA)
    	)).thenReturn(dataBD);
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.never()).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isWarn());
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.REM_ROWS_SIZE.toString()));
    	assertEquals(1, response.getData().get(SQLWatch.OutputParams.NEW_ROWS_SIZE.toString()));
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.MOD_ROWS_SIZE.toString()));
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.ROWS.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.SIZE_BACKUP.toString()));
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.SIZE_NEW_DATA.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.LOG_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.NEW_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.MOD_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.REM_ROWS.toString()));
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    @Test
	@DisplayName("SKIP SCHEMA - TABLE ALREADY PRESENT - with less data - with logRows")
    void case8() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 2));
    	List<Map<String, Object>> dataBD = new LinkedList<>();
    	dataBD.add(Map.of("col1", "val1", "col2", 1));
    	dataBD.add(Map.of("col1", "val2", "col2", 2));
    	dataBD.add(Map.of("col1", "val3", "col2", 3));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.LOG_ROWS.toString(), true);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
    	
    	Mockito.when(jdbcLocal.query(
		        Mockito.eq(SQL_TABLE_EXISTS),
		        Mockito.eq(new Object[] {SCHEMA, controlId.toLowerCase()}),
		        Mockito.eq(new int[] {Types.VARCHAR, Types.VARCHAR}),
		        Mockito.<ResultSetExtractor<Boolean>>any()
		)).thenReturn(TABLE_EXISTS);

    	Mockito.when(jdbcLocal.queryForList(
    	        Mockito.startsWith("SELECT * FROM " + SCHEMA)
    	)).thenReturn(dataBD);
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.never()).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isWarn());
    	assertEquals(1, response.getData().get(SQLWatch.OutputParams.REM_ROWS_SIZE.toString()));
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.NEW_ROWS_SIZE.toString()));
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.MOD_ROWS_SIZE.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.ROWS.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()));
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.SIZE_BACKUP.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.SIZE_NEW_DATA.toString()));
    	assertEquals(true, response.getData().get(SQLWatch.OutputParams.LOG_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.NEW_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.MOD_ROWS.toString()));
    	assertEquals(new LinkedList<Map<String, Object>>(List.of(dataBD.get(dataBD.size() - 1))), response.getData().get(SQLWatch.OutputParams.REM_ROWS.toString()));
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    @Test
	@DisplayName("SKIP SCHEMA - TABLE ALREADY PRESENT - with less data - with no logRows")
    void case9() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 2));
    	List<Map<String, Object>> dataBD = new LinkedList<>();
    	dataBD.add(Map.of("col1", "val1", "col2", 1));
    	dataBD.add(Map.of("col1", "val2", "col2", 2));
    	dataBD.add(Map.of("col1", "val3", "col2", 3));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.LOG_ROWS.toString(), false);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
    	
    	Mockito.when(jdbcLocal.query(
		        Mockito.eq(SQL_TABLE_EXISTS),
		        Mockito.eq(new Object[] {SCHEMA, controlId.toLowerCase()}),
		        Mockito.eq(new int[] {Types.VARCHAR, Types.VARCHAR}),
		        Mockito.<ResultSetExtractor<Boolean>>any()
		)).thenReturn(TABLE_EXISTS);

    	Mockito.when(jdbcLocal.queryForList(
    	        Mockito.startsWith("SELECT * FROM " + SCHEMA)
    	)).thenReturn(dataBD);
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.never()).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isWarn());
    	assertEquals(1, response.getData().get(SQLWatch.OutputParams.REM_ROWS_SIZE.toString()));
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.NEW_ROWS_SIZE.toString()));
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.MOD_ROWS_SIZE.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.ROWS.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()));
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.SIZE_BACKUP.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.SIZE_NEW_DATA.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.LOG_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.NEW_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.MOD_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.REM_ROWS.toString()));
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    @Test
	@DisplayName("SKIP SCHEMA - TABLE ALREADY PRESENT - with mod data - with logRows")
    void case10() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 100));
    	List<Map<String, Object>> dataBD = new LinkedList<>();
    	dataBD.add(Map.of("col1", "val1", "col2", 1));
    	dataBD.add(Map.of("col1", "val2", "col2", 2));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.LOG_ROWS.toString(), true);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
    	
    	Mockito.when(jdbcLocal.query(
		        Mockito.eq(SQL_TABLE_EXISTS),
		        Mockito.eq(new Object[] {SCHEMA, controlId.toLowerCase()}),
		        Mockito.eq(new int[] {Types.VARCHAR, Types.VARCHAR}),
		        Mockito.<ResultSetExtractor<Boolean>>any()
		)).thenReturn(TABLE_EXISTS);

    	Mockito.when(jdbcLocal.queryForList(
    	        Mockito.startsWith("SELECT * FROM " + SCHEMA)
    	)).thenReturn(dataBD);
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.never()).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isWarn());
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.REM_ROWS_SIZE.toString()));
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.NEW_ROWS_SIZE.toString()));
    	assertEquals(1, response.getData().get(SQLWatch.OutputParams.MOD_ROWS_SIZE.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.ROWS.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.SIZE_BACKUP.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.SIZE_NEW_DATA.toString()));
    	assertEquals(true, response.getData().get(SQLWatch.OutputParams.LOG_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.NEW_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.REM_ROWS.toString()));
    	assertEquals(new LinkedList<Map<String, Object>>(List.of(Map.of("col1", "val2", "col2", 100, "col2_OLD", 2))), response.getData().get(SQLWatch.OutputParams.MOD_ROWS.toString()));
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    @Test
	@DisplayName("SKIP SCHEMA - TABLE ALREADY PRESENT - with mod data - with no logRows")
    void case11() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 100));
    	List<Map<String, Object>> dataBD = new LinkedList<>();
    	dataBD.add(Map.of("col1", "val1", "col2", 1));
    	dataBD.add(Map.of("col1", "val2", "col2", 2));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.LOG_ROWS.toString(), false);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
    	
    	Mockito.when(jdbcLocal.query(
		        Mockito.eq(SQL_TABLE_EXISTS),
		        Mockito.eq(new Object[] {SCHEMA, controlId.toLowerCase()}),
		        Mockito.eq(new int[] {Types.VARCHAR, Types.VARCHAR}),
		        Mockito.<ResultSetExtractor<Boolean>>any()
		)).thenReturn(TABLE_EXISTS);

    	Mockito.when(jdbcLocal.queryForList(
    	        Mockito.startsWith("SELECT * FROM " + SCHEMA)
    	)).thenReturn(dataBD);
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.never()).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isWarn());
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.REM_ROWS_SIZE.toString()));
    	assertEquals(0, response.getData().get(SQLWatch.OutputParams.NEW_ROWS_SIZE.toString()));
    	assertEquals(1, response.getData().get(SQLWatch.OutputParams.MOD_ROWS_SIZE.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.ROWS.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.SIZE_BACKUP.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.SIZE_NEW_DATA.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.LOG_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.NEW_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.MOD_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.REM_ROWS.toString()));
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    @Test
	@DisplayName("SKIP SCHEMA - TABLE ALREADY PRESENT - with new repeated data - with logRows")
    void case12() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 2));
    	dataReturn.add(Map.of("col1", "val2", "col2", 3));
    	List<Map<String, Object>> dataBD = new LinkedList<>();
    	dataBD.add(Map.of("col1", "val1", "col2", 1));
    	dataBD.add(Map.of("col1", "val2", "col2", 2));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.LOG_ROWS.toString(), true);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
    	
    	Mockito.when(jdbcLocal.query(
		        Mockito.eq(SQL_TABLE_EXISTS),
		        Mockito.eq(new Object[] {SCHEMA, controlId.toLowerCase()}),
		        Mockito.eq(new int[] {Types.VARCHAR, Types.VARCHAR}),
		        Mockito.<ResultSetExtractor<Boolean>>any()
		)).thenReturn(TABLE_EXISTS);

    	Mockito.when(jdbcLocal.queryForList(
    	        Mockito.startsWith("SELECT * FROM " + SCHEMA)
    	)).thenReturn(dataBD);
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.never()).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isWarn());
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.ROWS.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.SIZE_BACKUP.toString()));
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.SIZE_NEW_DATA.toString()));
    	assertEquals(true, response.getData().get(SQLWatch.OutputParams.LOG_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.NEW_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.MOD_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.REM_ROWS.toString()));
    	List<List<Map<String, Object>>> expected = List.of(
		        List.of(
		            dataReturn.get(dataReturn.size() - 1),
		            dataReturn.get(dataReturn.size() - 2)
		        )
		    );
    	@SuppressWarnings("unchecked")
		List<List<Map<String, Object>>> actual = (List<List<Map<String, Object>>>)response.getData().get(SQLWatch.OutputParams.REP_NEW_DATA.toString());
    	assertThat(actual)
	        .usingRecursiveComparison()
	        .ignoringCollectionOrder()
	        .isEqualTo(expected);
	    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    @Test
	@DisplayName("SKIP SCHEMA - TABLE ALREADY PRESENT - with old repeated data - with logRows")
    void case13() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 2));
    	List<Map<String, Object>> dataBD = new LinkedList<>();
    	dataBD.add(Map.of("col1", "val1", "col2", 1));
    	dataBD.add(Map.of("col1", "val2", "col2", 2));
    	dataBD.add(Map.of("col1", "val2", "col2", 3));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.LOG_ROWS.toString(), true);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
    	
    	Mockito.when(jdbcLocal.query(
		        Mockito.eq(SQL_TABLE_EXISTS),
		        Mockito.eq(new Object[] {SCHEMA, controlId.toLowerCase()}),
		        Mockito.eq(new int[] {Types.VARCHAR, Types.VARCHAR}),
		        Mockito.<ResultSetExtractor<Boolean>>any()
		)).thenReturn(TABLE_EXISTS);

    	Mockito.when(jdbcLocal.queryForList(
    	        Mockito.startsWith("SELECT * FROM " + SCHEMA)
    	)).thenReturn(dataBD);
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.never()).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isWarn());
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.ROWS.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()));
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.SIZE_BACKUP.toString()));
    	assertEquals(2, response.getData().get(SQLWatch.OutputParams.SIZE_NEW_DATA.toString()));
    	assertEquals(true, response.getData().get(SQLWatch.OutputParams.LOG_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.NEW_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.MOD_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.REM_ROWS.toString()));
    	List<List<Map<String, Object>>> expected = List.of(
		        List.of(
		        	dataBD.get(dataBD.size() - 1),
		            dataBD.get(dataBD.size() - 2)
		        )
		    );
    	@SuppressWarnings("unchecked")
		List<List<Map<String, Object>>> actual = (List<List<Map<String, Object>>>)response.getData().get(SQLWatch.OutputParams.REP_BACKUP_DATA.toString());
    	assertThat(actual)
	        .usingRecursiveComparison()
	        .ignoringCollectionOrder()
	        .isEqualTo(expected);
	    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    @Test
	@DisplayName("SKIP SCHEMA - TABLE ALREADY PRESENT - with new and old repeated data - with logRows")
    void case14() {
    	String sql = "sql";
    	String controlId = "id_1";
    	String[] keys = new String[] {"col1"};
    	Object[] paramsSql = new Object[] {};
    	List<Map<String, Object>> dataReturn = new LinkedList<>();
    	dataReturn.add(Map.of("col1", "val1", "col2", 1));
    	dataReturn.add(Map.of("col1", "val2", "col2", 2));
    	dataReturn.add(Map.of("col1", "val2", "col2", 3));
    	List<Map<String, Object>> dataBD = new LinkedList<>();
    	dataBD.add(Map.of("col1", "val1", "col2", 1));
    	dataBD.add(Map.of("col1", "val2", "col2", 2));
    	dataBD.add(Map.of("col1", "val2", "col2", 3));
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.CONTROL_IDENTIFIER.toString(), controlId);
    	params.put(Params.LOG_ROWS.toString(), true);
    	params.put(Params.KEYS.toString(), keys);
    	params.put(Params.PARAMS_SQL.toString(), paramsSql);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
    	Mockito.when(jdbcLocal.query(
    	        Mockito.eq(SQL_SCHEMA_EXISTS),
    	        Mockito.eq(new String[] {SCHEMA}),
    	        Mockito.eq(new int[] {Types.VARCHAR}),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(SCHEMA_EXISTS);
        
    	Mockito.when(jdbcTemplate.queryForList(
    	        Mockito.eq(sql),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class)
    	)).thenReturn(dataReturn);
    	
    	Mockito.when(jdbcLocal.query(
		        Mockito.eq(SQL_TABLE_EXISTS),
		        Mockito.eq(new Object[] {SCHEMA, controlId.toLowerCase()}),
		        Mockito.eq(new int[] {Types.VARCHAR, Types.VARCHAR}),
		        Mockito.<ResultSetExtractor<Boolean>>any()
		)).thenReturn(TABLE_EXISTS);

    	Mockito.when(jdbcLocal.queryForList(
    	        Mockito.startsWith("SELECT * FROM " + SCHEMA)
    	)).thenReturn(dataBD);
    	
    	ControlResponse response = sqlWatch.execute(params);
    	
    	Mockito.verify(jdbcLocal, Mockito.never()).execute(
    			Mockito.eq(SQL_CREATE_SCHEMA)
    	);
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).queryForList(
    			Mockito.eq(sql),
    	        Mockito.eq(paramsSql),
    	        Mockito.eq(new int[] {})
    	);
    	
    	assertTrue(response.getStatus().isWarn());
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.ROWS.toString()));
    	assertEquals(false, response.getData().get(SQLWatch.OutputParams.FIRST_INVOCATION.toString()));
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.SIZE_BACKUP.toString()));
    	assertEquals(3, response.getData().get(SQLWatch.OutputParams.SIZE_NEW_DATA.toString()));
    	assertEquals(true, response.getData().get(SQLWatch.OutputParams.LOG_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.NEW_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.MOD_ROWS.toString()));
    	assertFalse(response.getData().containsKey(SQLWatch.OutputParams.REM_ROWS.toString()));

    	List<List<Map<String, Object>>> expectedNew = List.of(
		        List.of(
		            dataReturn.get(dataReturn.size() - 1),
		            dataReturn.get(dataReturn.size() - 2)
		        )
		    );
    	@SuppressWarnings("unchecked")
		List<List<Map<String, Object>>> actualNew = (List<List<Map<String, Object>>>)response.getData().get(SQLWatch.OutputParams.REP_NEW_DATA.toString());
    	assertThat(actualNew)
	        .usingRecursiveComparison()
	        .ignoringCollectionOrder()
	        .isEqualTo(expectedNew);
    	
    	List<List<Map<String, Object>>> expectedBD = List.of(
		        List.of(
		        	dataBD.get(dataBD.size() - 1),
		            dataBD.get(dataBD.size() - 2)
		        )
		    );
    	@SuppressWarnings("unchecked")
		List<List<Map<String, Object>>> actualBD = (List<List<Map<String, Object>>>)response.getData().get(SQLWatch.OutputParams.REP_BACKUP_DATA.toString());
    	assertThat(actualBD)
	        .usingRecursiveComparison()
	        .ignoringCollectionOrder()
	        .isEqualTo(expectedBD);
	    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    @Test
	@DisplayName("Coverage1")
    void coverage1() {
    	SQLWatch sqlWatch = new SQLWatch(jdbcLocal);
    	
		assertNotNull(sqlWatch.toString());
    }
}
