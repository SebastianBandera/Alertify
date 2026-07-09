package app.alertify.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import app.alertify.entity.repositories.ConfigRepositoryGlobal;

@Service
public class KeyProvider {

	private final String KEY_PART_CODE = "v1";
	private final String KEY_PART_DATABASE_KEY = "KEY_PART";
	
	@Value("${keyPart:default}")
	private String KEY_PART_ENVIRONMENT;
	
	private final ConfigRepositoryGlobal config;
	
	public KeyProvider(ConfigRepositoryGlobal config) {
		this.config = config;
	}
	
	public String getAESKey() {
		String keyPartDatabase = config.getString(KEY_PART_DATABASE_KEY);
		
		return keyPartDatabase + KEY_PART_CODE + KEY_PART_ENVIRONMENT;
	}
}
