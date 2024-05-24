package app.watchful.databases;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "datasources")
public class DataSourceProperties {

    private List<DataSourceConfig> datasources;

    public List<DataSourceConfig> getDatasources() {
        return datasources;
    }
    
    public void setConfig(Map<String, DataSourceBase> config) {
    	List<DataSourceConfig> result = new ArrayList<>();
    	
		if (config != null) {
			config.forEach((key, dsb) -> result.add(dsb.getDatasource()));
		}
		
		datasources = result;
	}
	
	@Data
    public static class DataSourceBase {
        private DataSourceConfig datasource;
    }

	@Data
    public static class DataSourceConfig {
        private String name;
        private Boolean readonly;
        private String url;
        private String username;
        private String password;
        private String driverClassName;
    }
}