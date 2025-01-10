package app.alertify.entity.repositories;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConfigRepositoryGlobal {

    private static final Logger log = LoggerFactory.getLogger(ConfigRepositoryGlobal.class);
	
	private final ConfigIntRepository configIntRepository;
    private final ConfigTextRepository configTextRepository;
    private final ConfigTimestampRepository configTimestampRepository;

    @Autowired
    public ConfigRepositoryGlobal(ConfigIntRepository configIntRepository, ConfigTextRepository configTextRepository, ConfigTimestampRepository configTimestampRepository) {
        this.configIntRepository = configIntRepository;
        this.configTextRepository = configTextRepository;
        this.configTimestampRepository = configTimestampRepository;
    }
    
    public Integer getInt(String paramName) {
    	log.info("Read Integer parameter: " + paramName);
    	return configIntRepository.findById(paramName).map(data -> data.getConfigInt()).orElse(null);
    }
    
    public Date getDate(String paramName) {
    	log.info("Read Date parameter: " + paramName);
    	return configTimestampRepository.findById(paramName).map(data -> data.getConfigTimestamp()).orElse(null);
    }
    
    public String getString(String paramName) {
    	log.info("Read String parameter: " + paramName);
    	return configTextRepository.findById(paramName).map(data -> data.getConfigText()).orElse(null);
    }
    
    
}
