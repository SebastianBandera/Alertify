package app.alertify.databases;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
	
    public static class DataSourceBase {
        private DataSourceConfig datasource;

		public DataSourceConfig getDatasource() {
			return datasource;
		}

		public void setDatasource(DataSourceConfig datasource) {
			this.datasource = datasource;
		}
    }

    public static class DataSourceConfig {
        private String name;
        private Boolean readonly;
        private String url;
        private String username;
        private String password;
        private String driverClassName;
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		public Boolean getReadonly() {
			return readonly;
		}
		public void setReadonly(Boolean readonly) {
			this.readonly = readonly;
		}
		public String getUrl() {
			return url;
		}
		public void setUrl(String url) {
			this.url = url;
		}
		public String getUsername() {
			return username;
		}
		public void setUsername(String username) {
			this.username = username;
		}
		public String getPassword() {
			return password;
		}
		public void setPassword(String password) {
			this.password = password;
		}
		public String getDriverClassName() {
			return driverClassName;
		}
		public void setDriverClassName(String driverClassName) {
			this.driverClassName = driverClassName;
		}
        
        
    }
}