package app.alertify.startup;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import app.alertify.databases.DataSources;

public class TerminateBean {
	
	private static final Logger log = LoggerFactory.getLogger(TerminateBean.class);
	
	@Autowired
	private DataSources dataSources;
	
    @PreDestroy
    public void onDestroy() throws Exception {
        log.info("TerminateBean:onDestroy() init");
        
        dataSources.close();
        
        log.info("TerminateBean:onDestroy() ends");
    }
}