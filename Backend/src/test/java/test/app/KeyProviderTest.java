package test.app;

import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import app.alertify.entity.repositories.ConfigRepositoryGlobal;
import app.crypto.KeyProvider;
import org.springframework.test.util.ReflectionTestUtils;

class KeyProviderTest {

    @Mock
    private ConfigRepositoryGlobal config;

    @InjectMocks
    private KeyProvider keyProvider;
    
    private String key = "";

    @BeforeEach
    void setUp() throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        MockitoAnnotations.openMocks(this);

        ReflectionTestUtils.setField(keyProvider, "KEY_PART_ENVIRONMENT", "EnvPart");
        
    	KeyProvider instance = new KeyProvider();
    	Field field = KeyProvider.class.getDeclaredField("KEY_PART_CODE");
		field.setAccessible(true);
		Object value = field.get(instance);
		if(value instanceof String) {
			key = (String)value;
		}
    }

    @Test
    void testGetAESKey() {
        String databaseKey = "DatabasePart";
        when(config.getString("KEY_PART")).thenReturn(databaseKey);

        String aesKey = keyProvider.getAESKey();

        String expectedKey = "DatabasePart" + key + "EnvPart";
        assertEquals(expectedKey, aesKey, "La clave AES generada no es la esperada");
    }
}
