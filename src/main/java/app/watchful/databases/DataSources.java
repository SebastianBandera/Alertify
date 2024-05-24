package app.watchful.databases;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import app.watchful.databases.DataSourceProperties.DataSourceConfig;

@Service
public class DataSources {

	@Autowired
	private DataSourceProperties dataSourceProperties;
	
	private Map<String, DataSource> dataSources = new HashMap<>();
	
	@PostConstruct
	private void init() {
		dataSourceProperties.getDatasources().forEach(this::register);
	}
	
	private void register(DataSourceConfig dataSourceConfig) {
		if (dataSources.containsKey(dataSourceConfig.getName())) {
			throw new RuntimeException(dataSourceConfig.getName() + " already registred!");
		}
		dataSources.put(dataSourceConfig.getName(), createDataSource(dataSourceConfig));
	}
	
	private DataSource createDataSource(DataSourceConfig config) {
		//DriverManagerDataSource
		HikariConfig hikariConfig = new HikariConfig();
		
		hikariConfig.setPoolName("HikariPool-" + config.getName());
		
		hikariConfig.setReadOnly(config.getReadonly());
		
		hikariConfig.setJdbcUrl(config.getUrl());
		hikariConfig.setDriverClassName(config.getDriverClassName());
		hikariConfig.setUsername(config.getUsername());
		hikariConfig.setPassword(config.getPassword());
		
        DataSource dataSource = new HikariDataSource(hikariConfig);
        
        try {
        	if(!dataSource.getConnection().isValid(2)) {
        		throw new RuntimeException(config.getName() + " no valid connection!");
            }
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(config.getName() + " error with connection!");
		}
        
        return dataSource;
    }

	public DataSource getDataSource(String key) {
		if(key == null) return null;
		return dataSources.get(key);
	}
}
