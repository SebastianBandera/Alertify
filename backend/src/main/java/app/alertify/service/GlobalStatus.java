package app.alertify.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GlobalStatus {
	
	private static final Logger log = LoggerFactory.getLogger(GlobalStatus.class);

	private boolean ready = false;
	
	public void setReady() {
		log.info("setReady true");
		ready = true;
	}
	
	public boolean isReady() {
		return ready;
	}
}
