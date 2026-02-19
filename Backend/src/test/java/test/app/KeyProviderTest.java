package test.app;

import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import app.alertify.crypto.KeyProvider;
import app.alertify.entity.repositories.ConfigRepositoryGlobal;

import org.springframework.test.util.ReflectionTestUtils;

class KeyProviderTest {

	private final String ENV_VALUE_TEST = "EnvPart";
	private final String DB_VALUE_TEST  = "DatabasePart";

	@Mock
    private ConfigRepositoryGlobal config;

    @InjectMocks
    private KeyProvider keyProvider;
    
    private String key = "";

    @BeforeEach
    void setUp() throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        MockitoAnnotations.openMocks(this);
        
        ReflectionTestUtils.setField(keyProvider, "KEY_PART_ENVIRONMENT", ENV_VALUE_TEST, String.class);
        
    	KeyProvider instance = new KeyProvider(config);
    	Field field = KeyProvider.class.getDeclaredField("KEY_PART_CODE");
		field.setAccessible(true);
		Object value = field.get(instance);
		if(value instanceof String) {
			key = (String)value;
		}
    }

    @Test
	@DisplayName("Prueba obtener la clave AES")
    void testGetAESKey() {
        when(config.getString("KEY_PART")).thenReturn(DB_VALUE_TEST);

        String aesKey = keyProvider.getAESKey();

        String expectedKey = DB_VALUE_TEST + key + ENV_VALUE_TEST;
        assertEquals(expectedKey, aesKey, "La clave AES generada no es la esperada");
    }
}
