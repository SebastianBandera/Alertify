package app.alertify.crypto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import app.alertify.entity.repositories.ConfigRepositoryGlobal;

@Service
public class KeyProvider {

	private final String KEY_PART_CODE = "3845yhgdf9F))(DH&%(SDFJGFDD";
	private final String KEY_PART_DATABASE_KEY = "KEY_PART";
	
	@Value("${keyPart:default}")
	private String KEY_PART_ENVIRONMENT;
	
	@Autowired
	private ConfigRepositoryGlobal config;
	
	public KeyProvider() {
		int debug = -1;
	}
	
	public String getAESKey() {
		String keyPartDatabase = config.getString(KEY_PART_DATABASE_KEY);
		
		return keyPartDatabase + KEY_PART_CODE + KEY_PART_ENVIRONMENT;
	}
}
