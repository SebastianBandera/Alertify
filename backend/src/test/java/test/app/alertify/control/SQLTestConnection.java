package test.app.alertify.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.control.ControlResponse;
import app.alertify.control.generic.SQLWatch.Params;
import app.alertify.control.generic.TestConnection;

@ExtendWith(MockitoExtension.class)
public class SQLTestConnection {

	@Mock
	private Connection connection;
	
	@Mock
    private DataSource dataSource;
	
    @InjectMocks
    private TestConnection testConnection;
    
    @Test
	@DisplayName("Sí Conecta")
    void connectOk() throws SQLException {
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(dataSource.getConnection()).thenReturn(connection);
    	
    	Mockito.when(connection.isValid(Mockito.anyInt())).thenReturn(true);
    	
    	ControlResponse response = testConnection.execute(params);
    	
    	Mockito.verify(dataSource, Mockito.times(1)).getConnection();
    	
    	Mockito.verify(connection, Mockito.times(1)).isValid(
    			Mockito.anyInt()
    	);
    
    	assertTrue(response.getStatus().isSuccess());
    	
    	Mockito.verifyNoMoreInteractions(dataSource, connection);
    }
    
    @Test
	@DisplayName("No Conecta")
    void connectFail() throws SQLException {
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(dataSource.getConnection()).thenReturn(connection);
    	
    	Mockito.when(connection.isValid(Mockito.anyInt())).thenReturn(false);
    	
    	ControlResponse response = testConnection.execute(params);
    	
    	Mockito.verify(dataSource, Mockito.times(1)).getConnection();
    	
    	Mockito.verify(connection, Mockito.times(1)).isValid(
    			Mockito.anyInt()
    	);
    
    	assertTrue(response.getStatus().isWarn());
    	
    	Mockito.verifyNoMoreInteractions(dataSource, connection);
    }
    
    @Test
	@DisplayName("ConnectError")
    void connectError() throws SQLException {
    	String msgError = "msgError";
    	
    	Map<String, Object> params = new HashMap<String, Object>();
    	params.put(Params.DATA_SOURCE.toString(), dataSource);
    	
    	Mockito.when(dataSource.getConnection()).thenReturn(connection);
    	
    	Mockito.when(connection.isValid(Mockito.anyInt())).thenThrow(new SQLException(msgError));
    	
    	ControlResponse response = testConnection.execute(params);
    	
    	Mockito.verify(dataSource, Mockito.times(1)).getConnection();
    	
    	Mockito.verify(connection, Mockito.times(1)).isValid(
    			Mockito.anyInt()
    	);
    
    	assertTrue(response.getStatus().isWarn());
    	
    	Mockito.verifyNoMoreInteractions(dataSource, connection);
    }
}
