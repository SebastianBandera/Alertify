package app.alertify.startup;

import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class StartupComponent implements ApplicationRunner  {
	
	@Autowired
	private StartupProcess startupProcess;
	
    @Override
    public void run(ApplicationArguments args) throws Exception {
    	log(args);
    	
    	Thread th = new Thread(startupProcess::runnable, "Startup");
    	
    	th.start();
    }
    
    private void log(ApplicationArguments args) {
    	if(args == null)
    		log.info("empty args");
    	else
    		log.info("getSourceArgs(): " + (args.getSourceArgs()==null ? "null" : (args.getSourceArgs().length==0 ? "empty" : String.join(",", args.getSourceArgs()))));
    }
}