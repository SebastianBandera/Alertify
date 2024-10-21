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
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DataSources {

	@Autowired
	private DataSourceProperties dataSourceProperties;
	
	private Map<String, CloseableDataSource> dataSources = new HashMap<>();
	
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
	
	@SuppressWarnings("resource")
	private CloseableDataSource createDataSource(DataSourceConfig config) {		
		//DriverManagerDataSource
		HikariConfig hikariConfig = new HikariConfig();
		
		hikariConfig.setPoolName("HikariPool-" + config.getName());
		
		hikariConfig.setReadOnly(config.getReadonly());
		
		hikariConfig.setJdbcUrl(config.getUrl());
		hikariConfig.setDriverClassName(config.getDriverClassName());
		hikariConfig.setUsername(config.getUsername());
		hikariConfig.setPassword(config.getPassword());
		
		HikariDataSource dataSource = new HikariDataSource(hikariConfig);
		
        try {
        	if(!dataSource.getConnection().isValid(2)) {
        		throw new RuntimeException(config.getName() + " no valid connection!");
            }
		} catch (Exception e) {
			e.printStackTrace();
			log.error("Error", e);
			throw new RuntimeException(config.getName() + " error with connection!");
		}
        
        return new CloseableDataSource(dataSource);
    }

	public DataSource getDataSource(String key) {
		if(key == null) return null;
		return dataSources.get(key);
	}
	
	public void close() {
		dataSources.values().forEach(this::fancyClose);
	}
	
	private void fancyClose(CloseableDataSource dataSource) {
		try {
			dataSource.close();
		} catch (Exception e) {
			e.printStackTrace();
			log.error("Error closing connection " + dataSource, e);
		}
	}
}
