package app.watchful.startup;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import app.watchful.service.GlobalStatus;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class StartupProcess {

	@Autowired
	private GlobalStatus globalStatus;
	
    public void runnable() {
    	log.info("Startup");
    	
    	
    	
    	globalStatus.setReady();
    	
    	log.info("Startup ends");
    }
}
