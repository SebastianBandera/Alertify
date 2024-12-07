package app.alertify.service;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class GlobalStatus {

	private boolean ready = false;
	
	public void setReady() {
		log.info("setReady true");
		ready = true;
	}
	
	public boolean isReady() {
		return ready;
	}
}
