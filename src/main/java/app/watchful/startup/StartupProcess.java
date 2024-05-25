package app.watchful.startup;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import app.watchful.databases.DataSources;
import app.watchful.service.GlobalStatus;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class StartupProcess {

	@Autowired
	private GlobalStatus globalStatus;
	
	@Autowired
	private DataSources dataSources;
	
	@Autowired
	private DataSource primaryDataSource;
	
    public void runnable() {
    	log.info("Startup");
    	
    	
    	
    	globalStatus.setReady();
    	
    	log.info("Startup ends");
    }
}
