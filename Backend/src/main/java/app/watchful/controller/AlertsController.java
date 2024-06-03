package app.watchful.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import app.watchful.control.common.StringUtils;
import app.watchful.entity.Alert;
import app.watchful.entity.repositories.AlertRepository;
import app.watchful.service.ThreadControl;
import app.watchful.startup.StartupProcess;

@RestController
public class AlertsController {

	@Autowired
	private AlertRepository alertRepository;
	
	@SuppressWarnings("unused")
	@Autowired
	private ThreadControl threadControl;
	
	@Autowired
	private StartupProcess startupProcess;
	
	@PostMapping("/alerts/reload")
	public ResponseEntity<String> reload() {
		List<Alert> dbAlerts = alertRepository.findAll();
		
		dbAlerts.forEach(startupProcess::registerAlert);
		
		return ResponseEntity.ok(StringUtils.concat(String.valueOf(dbAlerts.size()), " alerts processed"));
	}
	
}
