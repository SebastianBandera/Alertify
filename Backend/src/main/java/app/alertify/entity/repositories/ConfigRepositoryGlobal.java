package app.alertify.entity.repositories;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConfigRepositoryGlobal {
	
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
    	return configIntRepository.findById(paramName).map(data -> data.getConfigInt()).orElse(null);
    }
    
    public Date getDate(String paramName) {
    	return configTimestampRepository.findById(paramName).map(data -> data.getConfigTimestamp()).orElse(null);
    }
    
    public String getString(String paramName) {
    	return configTextRepository.findById(paramName).map(data -> data.getConfigText()).orElse(null);
    }
    
    
}
