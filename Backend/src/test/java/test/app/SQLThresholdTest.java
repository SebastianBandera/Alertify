package test.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import app.alertify.control.ControlResponse;
import app.alertify.control.generic.SQLThreshold;
import app.alertify.control.generic.SQLThreshold.Params;

@ExtendWith(MockitoExtension.class)
public class SQLThresholdTest {
	
	@Mock
    private DataSource dataSource;
    
	@Mock
    private JdbcTemplate jdbcTemplate;
    
    @InjectMocks
    private SQLThreshold sqlThreshold;
    
    @BeforeEach
    void setup() {
        Function<DataSource, JdbcTemplate> supplier = _ -> jdbcTemplate;
        sqlThreshold = new SQLThreshold(supplier);
    }

    @Test
	@DisplayName("Falla por error en parametros - null")
    void fallaParametrosNull() {
		assertThrows(Exception.class, () -> sqlThreshold.execute(null), "Se esperaba una excepción por no tener los parámetros correctos");
    }

    @Test
	@DisplayName("Falla por error en parametros - empty")
    void fallaParametrosEmpty() {
    	Map<String, Object> params = new HashMap<String, Object>();
		assertThrows(Exception.class, () -> sqlThreshold.execute(params), "Se esperaba una excepción por no tener los parámetros correctos");
    }

    @Test
	@DisplayName("Success - warn_if_equal - distinct")
    void success1() {
    	String type = "warn_if_equal";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold + 1); //Avoid equal
        
    	ControlResponse response = sqlThreshold.execute(params);

        assertTrue(response.getStatus().isSuccess());
    }
    
    @Test
	@DisplayName("Success - warn_if_equal - warn")
    void success2() {
    	String type = "warn_if_equal";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold);
        
    	ControlResponse response = sqlThreshold.execute(params);

        assertTrue(response.getStatus().isWarn());
    }
    
    @Test
	@DisplayName("Success - warn_if_distinct - distinct")
    void success3() {
    	String type = "warn_if_distinct";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold + 1); //Avoid equal
        
    	ControlResponse response = sqlThreshold.execute(params);

        assertTrue(response.getStatus().isWarn());
    }
    
    @Test
	@DisplayName("Success - warn_if_distinct - warn")
    void success4() {
    	String type = "warn_if_distinct";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold);
        
    	ControlResponse response = sqlThreshold.execute(params);

        assertTrue(response.getStatus().isSuccess());
    }
    
    @Test
	@DisplayName("Success - warn_if_bigger - bigger")
    void success5() {
    	String type = "warn_if_bigger";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold + 1);
        
    	ControlResponse response = sqlThreshold.execute(params);

        assertTrue(response.getStatus().isWarn());
    }
    
    @Test
	@DisplayName("Success - warn_if_bigger - not bigger")
    void success6() {
    	String type = "warn_if_bigger";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold);
        
    	ControlResponse response = sqlThreshold.execute(params);

        assertTrue(response.getStatus().isSuccess());
    }
    
    @Test
	@DisplayName("Success - warn_if_lower - lower")
    void success7() {
    	String type = "warn_if_lower";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold - 1);
        
    	ControlResponse response = sqlThreshold.execute(params);

        assertTrue(response.getStatus().isWarn());
    }
    
    @Test
	@DisplayName("Success - warn_if_lower - not lower")
    void success8() {
    	String type = "warn_if_lower";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold);
        
    	ControlResponse response = sqlThreshold.execute(params);

        assertTrue(response.getStatus().isSuccess());
    }
    
    @Test
	@DisplayName("Coverage1")
    void coverage1() {
    	String type = "warn_if_lower";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	sqlThreshold = new SQLThreshold();
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
        
		assertThrows(CannotGetJdbcConnectionException.class, () -> sqlThreshold.execute(params), "Se esperaba una excepción por JDBC");
    }
    
    @Test
	@DisplayName("Falla por type no reconocido")
    void fail1() {
    	String type = "unknown";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold);

		assertThrows(Exception.class, () -> sqlThreshold.execute(params), "Se esperaba una excepción por no tener un type reconocido");
    }
    
    @Test
	@DisplayName("Assert output params")
    void asserts1() {
    	String type = "warn_if_equal";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold);
        
    	ControlResponse response = sqlThreshold.execute(params);

        assertEquals(desc, response.getData().get(SQLThreshold.OutputParams.DESCRIPCION.toString()));
        assertEquals(threshold, response.getData().get(SQLThreshold.OutputParams.THRESHOLD.toString()));
        assertEquals(type, response.getData().get(SQLThreshold.OutputParams.THRESHOLD_TYPE.toString()));
    }
    
    @Test
	@DisplayName("Assert output params with getValue()")
    void asserts2() {
    	String type = "warn_if_equal";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold);
        
    	ControlResponse response = sqlThreshold.execute(params);

        assertEquals(desc, response.getData().get(SQLThreshold.OutputParams.DESCRIPCION.getValue()));
        assertEquals(threshold, response.getData().get(SQLThreshold.OutputParams.THRESHOLD.getValue()));
        assertEquals(type, response.getData().get(SQLThreshold.OutputParams.THRESHOLD_TYPE.getValue()));
    }
    
    @Test
	@DisplayName("Assert output params with getValue()")
    void assertsSQLParams() {
    	String type = "warn_if_equal";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	Object[] sqlParams = new Object[] {"a", "b", "c"};
    	int[] types = new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR};
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), sqlParams);
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(threshold);
        
    	sqlThreshold.execute(params);    	
    	
    	Mockito.verify(jdbcTemplate, Mockito.times(1)).query(
        		Mockito.eq(sql),
        		Mockito.eq(sqlParams),
        		Mockito.eq(types),
        		Mockito.<ResultSetExtractor<Integer>>any()
        );

    	Mockito.verifyNoMoreInteractions(jdbcTemplate);
    }
    
    @Test
	@DisplayName("Assert output count is -1 when sql return null")
    void assertsDefaultvalueSqlMiss() {
    	String type = "warn_if_equal";
    	String desc = "desc";
    	String sql = "sql";
    	int threshold = 10;
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.THRESHOLD.toString(), threshold);
    	params.put(Params.THRESHOLD_TYPE.toString(), type);
    	params.put(Params.DESCRIPCION.toString(), desc);
    	params.put(Params.SQL.toString(), sql);
    	params.put(Params.PARAMS_SQL.toString(), new Object[] {});
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(jdbcTemplate.query(
    	        Mockito.anyString(),
    	        Mockito.any(Object[].class),
    	        Mockito.any(int[].class),
    	        Mockito.<ResultSetExtractor<Integer>>any()
    	)).thenReturn(null);
        
    	sqlThreshold.execute(params);   
        
    	ControlResponse response = sqlThreshold.execute(params);
    	
        assertTrue(response.getStatus().isSuccess());
        assertEquals(-1, response.getData().get(SQLThreshold.OutputParams.COUNT.toString()));
    }
}
