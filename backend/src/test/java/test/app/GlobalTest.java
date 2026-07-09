package test.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;

import app.alertify.config.Global;

class GlobalTest {

    @InjectMocks
    private Global global;
    
    @SuppressWarnings("static-access")
	@Test
	@DisplayName("Prueba de Global.getAppCharset() entre instancia y estático")
    void testGetAESKey() {
        Global global = new Global();
        
        assertEquals(global.getAppCharset(), Global.getAppCharset(), "La clave AES generada no es la esperada");
    }
}
