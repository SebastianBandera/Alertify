package test.app;

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

import org.aspectj.apache.bcel.generic.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.control.ControlResponse;
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
    	
    	Mockito.verifyNoMoreInteractions(jdbcLocal, jdbcTemplate);
    }
    
    
}
