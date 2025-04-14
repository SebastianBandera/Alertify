package app.alertify.databases;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;

import app.alertify.crypto.Crypto;
import app.alertify.crypto.CryptoMessage;
import app.alertify.crypto.KeyProvider;
import app.alertify.databases.DataSourceProperties.DataSourceConfig;
import app.alertify.entity.BasicSecret;
import app.alertify.entity.DbSource;
import app.alertify.entity.repositories.DBSourceRepository;

@Service
public class DataSources {

	private static final Logger log = LoggerFactory.getLogger(DataSources.class);
	
	@Autowired
	private DBSourceRepository repository;

	@Autowired
	private KeyProvider keyProvider;

	private DataSourceProperties dataSourceProperties;
	
	private Map<String, CloseableDataSource> dataSources = new HashMap<>();
	
	public DataSources() {
		dataSourceProperties = new DataSourceProperties();
	}
	
	@PostConstruct
	private void init() {
		checkDataSourceProperties();
		
		dataSourceProperties.getDatasources().forEach(this::register);
	}

	public void recheckDataSourceProperties() {
		log.info("DataSources.recheckDataSourceProperties");
		
		checkDataSourceProperties();
	}
	
	public DataSource getDataSource(String key) {
		if(key == null) return null;
		return dataSources.get(key);
	}

	public void close() {
		dataSources.values().forEach(this::fancyClose);
	}

	private void checkDataSourceProperties() {
		final List<DbSource> allDB           = repository.findAll();
		final Set<String>    registredMemory = dataSourceProperties.getDatasources().stream().map(config -> config.getName()).collect(Collectors.toSet());
		
		for (DbSource dbConfig: allDB) {
			process(dbConfig, registredMemory);
		}
		
		//Quita los datasources en memoria que dejaron de existir en la DB, luego de cerrar la conexión.
		registredMemory.forEach(name -> {
			fancyClose(dataSources.remove(name));
			dataSourceProperties.removeByName(name);
		});
	}

	private void process(DbSource dbConfig, Set<String> registredMemory) {
		try {
			String name = dbConfig.getName();
					
			preprocessPassword(dbConfig);
			
			boolean alreadyRegistred = registredMemory.contains(name);
			
			if(alreadyRegistred) {
				processAlreadyRegistred(dbConfig, registredMemory);
			} else {
				processNewDBConfig(dbConfig, registredMemory);
			}
			
			registredMemory.remove(name);
		} catch (Exception e) {
			log.error("Error al procesar una configuración de base de datos", e);
		}

	}

	private void preprocessPassword(DbSource dbConfig) throws Exception {
		boolean needUpdate = false;
		if (dbConfig.getBasicSecretUsername().getSecretStatus() == BasicSecret.SECRET_STATUS_PLAIN) {
			updatePasswordAES_SHA256_IV(dbConfig, dbConfig.getBasicSecretUsername());
			needUpdate |= true;
		}
		if (dbConfig.getBasicSecretPassword().getSecretStatus() == BasicSecret.SECRET_STATUS_PLAIN) {
			updatePasswordAES_SHA256_IV(dbConfig, dbConfig.getBasicSecretPassword());
			needUpdate |= true;
		}
		
		if (needUpdate) {
			repository.saveAndFlush(dbConfig);
		}
	}

	private void updatePasswordAES_SHA256_IV(DbSource dbConfig, BasicSecret basicSecret) throws Exception {
		String plainPassword = basicSecret.getSecret();
		
		CryptoMessage message = Crypto.encriptar(plainPassword, keyProvider.getAESKey());
		
		String newPasswordBody = Crypto.empaquetarIV(message.getMessage(), message.getIv());
		
		basicSecret.setSecretStatus(BasicSecret.SECRET_STATUS_ENCRYPTED_AES_SHA256_IV);
		basicSecret.setSecret(newPasswordBody);
	}

	private void processNewDBConfig(DbSource dbConfig, Set<String> registredMemory) throws Exception {
		DataSourceConfig dbConfigMemory = new DataSourceConfig();
		
		dbConfigMemory.setDriverClassName(dbConfig.getDriverClassName());
		dbConfigMemory.setName(dbConfig.getName());
		dbConfigMemory.setReadonly(dbConfig.isReadonly());
		dbConfigMemory.setUrl(dbConfig.getUrl());
		dbConfigMemory.setUsername(dbConfig.getBasicSecretUsername().getSecret());
		dbConfigMemory.setPassword(dbConfig.getBasicSecretPassword().getSecret());
		
		dataSourceProperties.getDatasources().add(dbConfigMemory);
		
		log.info("Registering connection '" + dbConfig.getName() + "'");
	}

	private void processAlreadyRegistred(DbSource dbConfig, Set<String> registredMemory) {
		DataSourceConfig dbConfigMemory = new DataSourceConfig();
		
		dbConfigMemory.setDriverClassName(dbConfig.getDriverClassName());
		dbConfigMemory.setName(dbConfig.getName());
		dbConfigMemory.setReadonly(dbConfig.isReadonly());
		dbConfigMemory.setUrl(dbConfig.getUrl());
		dbConfigMemory.setUsername(dbConfig.getBasicSecretUsername().getSecret());
		dbConfigMemory.setPassword(dbConfig.getBasicSecretPassword().getSecret());
		
		String name = dbConfig.getName();
		
		DataSourceConfig currentData = dataSourceProperties.getDatasources().stream().filter(item -> item.getName() != null && item.getName().equals(name)).findAny().orElse(null);
		
		if(currentData == null || !currentData.equals(dbConfigMemory)) {
			log.info("Reloading connection '" + name + "'");
			
			fancyClose(dataSources.remove(name));
			dataSourceProperties.removeByName(name);
			
			dataSourceProperties.getDatasources().add(dbConfigMemory);
		}
	}

	private void register(DataSourceConfig dataSourceConfig) {
		if (dataSources.containsKey(dataSourceConfig.getName())) {
			throw new RuntimeException(dataSourceConfig.getName() + " already registred!");
		}
		dataSources.put(dataSourceConfig.getName(), createDataSource(dataSourceConfig));
	}
	
	private CloseableDataSource createDataSource(DataSourceConfig config) {		
		//DriverManagerDataSource
		HikariConfig hikariConfig = new HikariConfig();
		
		hikariConfig.setPoolName("HikariPool-" + config.getName());
		
		hikariConfig.setReadOnly(config.getReadonly());
		
		hikariConfig.setJdbcUrl(config.getUrl());
		hikariConfig.setDriverClassName(config.getDriverClassName());
		
		try {
			hikariConfig.setUsername(Crypto.desencriptar(config.getUsername(), keyProvider.getAESKey()));
		} catch (Exception e) {
			log.error("Error al desencriptar el usuario", e);
		}
		
		try {
			hikariConfig.setPassword(Crypto.desencriptar(config.getPassword(), keyProvider.getAESKey()));
		} catch (Exception e) {
			log.error("Error al desencriptar la contraseña", e);
		}
		
		HikariDataSource dataSource = null;
		
		try {
			dataSource = new HikariDataSource(hikariConfig);
		} catch (PoolInitializationException e) {
			dataSource = new RecoverableHikiariDataSource(config.getName(), () -> new HikariDataSource(hikariConfig));
		}
        
        return new CloseableDataSource(dataSource);
    }

	private void fancyClose(CloseableDataSource dataSource) {
		try {
			dataSource.close();
		} catch (Exception e) {
			log.error("Error closing connection " + dataSource, e);
		}
	}
}
