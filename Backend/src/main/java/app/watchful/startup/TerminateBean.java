package app.watchful.startup;

import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;

import app.watchful.databases.DataSources;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TerminateBean {

	@Autowired
	private DataSources dataSources;
	
    @PreDestroy
    public void onDestroy() throws Exception {
        log.info("TerminateBean:onDestroy() init");
        
        dataSources.close();
        
        log.info("TerminateBean:onDestroy() ends");
    }
}