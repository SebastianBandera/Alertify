package app.alertify.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.control.common.StringUtils;
import app.alertify.controller.dto.AlertDto;
import app.alertify.controller.dto.AlertResultDto;
import app.alertify.controller.dto.GUIAlertGroupDto;
import app.alertify.controller.dto.MapperConfig;
import app.alertify.controller.dto.SimpleMapper;
import app.alertify.controller.dto.summary.v1.CheckGroups;
import app.alertify.entity.Alert;
import app.alertify.entity.AlertResult;
import app.alertify.entity.repositories.AlertRepository;
import app.alertify.entity.repositories.AlertResultRepository;
import app.alertify.entity.repositories.GUIAlertGroupRepository;
import app.alertify.service.AlertService;
import app.alertify.service.ThreadControl;
import app.alertify.startup.StartupProcess;

@RestController
public class AlertsController {
	
	@Value("${pageSize:#{10}}")
	private int pageSize;

	@Autowired
	private AlertRepository alertRepository;

	@Autowired
	private AlertResultRepository alertResultsRepository;

	@Autowired
	private GUIAlertGroupRepository guiAlertGroupRepository;
	
	@Autowired
	private AlertService alertService;
	
	@SuppressWarnings("unused")
	@Autowired
	private ThreadControl threadControl;
	
	@Autowired
	private StartupProcess startupProcess;
	
	@Autowired
	private SimpleMapper simpleMapper;
	
	@Autowired
	private MapperConfig mapperConfig;
	
	@PostMapping("alerts/reload")
	public ResponseEntity<String> reload() {
		List<Alert> dbAlerts = alertRepository.findAll();
		
		dbAlerts.forEach(startupProcess::registerAlert);
		
		return ResponseEntity.ok(StringUtils.concat(String.valueOf(dbAlerts.size()), " alerts processed"));
	}

	@GetMapping("/alerts")
	public ResponseEntity<Page<AlertDto>> all(@RequestParam(defaultValue = "0") int page) {
		if (page < 0) return ResponseEntity.badRequest().build();
		return ResponseEntity.ok(alertRepository.findAll(PageRequest.of(page, pageSize, Sort.by(Sort.Order.desc("id")))).map(in -> simpleMapper.map(in, AlertDto.class)));
	}
	
	@GetMapping("/alerts/summary/v1")
	public ResponseEntity<CheckGroups> summary_v1() {
		return ResponseEntity.ok(alertService.summaryV1());
	}

	@GetMapping("/alerts/groups")
	public ResponseEntity<Page<GUIAlertGroupDto>> allGroups(@RequestParam(defaultValue = "0") int page) {
		if (page < 0) return ResponseEntity.badRequest().build();
		return ResponseEntity.ok(guiAlertGroupRepository.findAll(PageRequest.of(page, pageSize, Sort.by(Sort.Order.desc("id")))).map(in -> simpleMapper.map(in, GUIAlertGroupDto.class, mapperConfig.getMapping())));
	}

	@GetMapping("/alerts/results")
	public ResponseEntity<Page<AlertResultDto>> allResults(@RequestParam(defaultValue = "0") int page) {
		if (page < 0) return ResponseEntity.badRequest().build();
		return ResponseEntity.ok(alertResultsRepository.findAll(PageRequest.of(page, pageSize, Sort.by(Sort.Order.desc("id")))).map(in -> simpleMapper.map(in, AlertResultDto.class, mapperConfig.getMapping())));
	}
	
	@PostMapping("/alerts/results/{id}/resolve")
	public ResponseEntity<Object> resolveResult(@PathVariable Long id) {
		if (id == null || id < 0) return ResponseEntity.badRequest().build();
		Optional<AlertResult> r = alertResultsRepository.findById(id);
		if (r.isPresent()) {
			AlertResult alertResult = r.get();
			if (alertResult.isNeeds_review()) {
				alertResult.setNeeds_review(false);
				alertResultsRepository.saveAndFlush(alertResult);
			}
			return ResponseEntity.ok().build();
		} else {
			return ResponseEntity.notFound().build();
		}		
	}
	
}
