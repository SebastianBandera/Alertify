package app.watchful.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import app.watchful.entity.Alert;
import app.watchful.entity.repositories.AlertRepository;
import app.watchful.service.ThreadControl;
import app.watchful.startup.StartupProcess;
import common.string.StringUtils;

@RestController
public class AlertsController {

	@Autowired
	private AlertRepository alertRepository;
	
	@Autowired
	private ThreadControl threadControl;
	
	@Autowired
	private StartupProcess startupProcess;
	
	@GetMapping("/alerts/reload")
	public ResponseEntity<String> reload() {
		List<Alert> dbAlerts = alertRepository.findAll();
		List<Alert> registredAlerts = threadControl.getRegistredAlerts();
		
		dbAlerts.removeAll(registredAlerts);
		
		dbAlerts.forEach(startupProcess::registerAlert);
		
		return ResponseEntity.ok(StringUtils.concat(String.valueOf(dbAlerts.size()), " alerts added"));
	}
	
}
