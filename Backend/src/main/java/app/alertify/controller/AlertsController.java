package app.alertify.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import javax.management.AttributeNotFoundException;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.controller.dto.AlertDto;
import app.alertify.controller.dto.AlertResultDto;
import app.alertify.controller.dto.DateDto;
import app.alertify.controller.dto.GUIAlertGroupDto;
import app.alertify.controller.dto.MapperConfig;
import app.alertify.controller.dto.SimpleMapper;
import app.alertify.controller.dto.summary.v1.CheckGroups;
import app.alertify.entity.Alert;
import app.alertify.entity.AlertResult;
import app.alertify.entity.GUIAlertGroup;
import app.alertify.entity.repositories.custom.DynamicSearchResult;
import app.alertify.entity.repositories.custom.DynamicSearchResultDto;
import app.alertify.entity.repositories.extended.AlertRepositoryExtended;
import app.alertify.entity.repositories.extended.AlertResultRepositoryExtended;
import app.alertify.entity.repositories.extended.GUIAlertGroupRepositoryExtended;
import app.alertify.service.AlertService;
import app.alertify.service.ThreadControl;

@RestController
public class AlertsController {
	
    @PersistenceContext
    private EntityManager entityManager;

	@Autowired
	private AlertRepositoryExtended alertRepositoryExtended;
	
	@Autowired
	private AlertResultRepositoryExtended alertResultRepositoryExtended;

	@Autowired
	private GUIAlertGroupRepositoryExtended guiAlertGroupRepositoryExtended;
	
	@Autowired
	private AlertService alertService;
	
	@SuppressWarnings("unused")
	@Autowired
	private ThreadControl threadControl;

	@Autowired
	private SimpleMapper simpleMapper;
	
	@Autowired
	private MapperConfig mapperConfig;
	
	@PostMapping("alerts/reload")
	public ResponseEntity<String> reload() {
		return ResponseEntity.ok(alertService.reload());
	}

	@GetMapping("/alerts")
	public ResponseEntity<DynamicSearchResultDto<AlertDto>> all(@RequestParam(defaultValue = "0") int page, @RequestParam MultiValueMap<String, String> params) {
		Supplier<DynamicSearchResult<Alert>> supplier = () -> alertRepositoryExtended.customSearch(PageRequest.of(page, alertService.getPageSize(), Sort.by(Sort.Order.desc("id"))), params, Alert.class, null);
		
		return commonResponse(page, params, true, AlertDto.class, supplier);
	}
	
	@GetMapping("/alerts/summary/v1")
	public ResponseEntity<CheckGroups> summary_v1() {
		return ResponseEntity.ok(alertService.summaryV1());
	}

	@GetMapping("/alerts/groups")
	public ResponseEntity<DynamicSearchResultDto<GUIAlertGroupDto>> allGroups(@RequestParam(defaultValue = "0") int page, @RequestParam MultiValueMap<String, String> params) {
		Supplier<DynamicSearchResult<GUIAlertGroup>> supplier = () -> guiAlertGroupRepositoryExtended.customSearch(PageRequest.of(page, alertService.getPageSize(), Sort.by(Sort.Order.asc("name"))), params, GUIAlertGroup.class, null);
		
		return commonResponse(page, params, true, GUIAlertGroupDto.class, supplier);
	}

	@GetMapping("/alerts/nogroups")
	public ResponseEntity<DynamicSearchResultDto<AlertDto>> noGroups(@RequestParam(defaultValue = "0") int page, @RequestParam MultiValueMap<String, String> params) {
		Supplier<DynamicSearchResult<Alert>> supplier = () -> {
			List<BiFunction<Root<Alert>, CriteriaQuery<Alert>, Predicate>> fixedPredicates = new ArrayList<>();
			BiFunction<Root<Alert>, CriteriaQuery<Alert>, Predicate> funcRootToAlert = (root, query) -> {
				CriteriaBuilder cb = entityManager.getCriteriaBuilder();
				Subquery<Long> subquery = query.subquery(Long.class);
				Root<GUIAlertGroup> groupRoot = subquery.from(GUIAlertGroup.class);
				subquery.select(groupRoot.get("alert").get("id"));
				subquery.where(cb.isTrue(groupRoot.get("active")));
				
				return cb.not(root.get("id").in(subquery));
			};
			
			fixedPredicates.add(funcRootToAlert);
			
			return alertRepositoryExtended.customSearch(PageRequest.of(page, alertService.getPageSize(), Sort.by(Sort.Order.desc("id"))), params, Alert.class, fixedPredicates);
		};
		
		return commonResponse(page, params, true, AlertDto.class, supplier);
	}

	@GetMapping("/alerts/results")
	public ResponseEntity<DynamicSearchResultDto<AlertResultDto>> allResults(@RequestParam(defaultValue = "0") int page, @RequestParam MultiValueMap<String, String> params) {
		Supplier<DynamicSearchResult<AlertResult>> supplier = () -> alertResultRepositoryExtended.customSearch(PageRequest.of(page, alertService.getPageSize(), Sort.by(Sort.Order.desc("id"))), params, AlertResult.class, null);
		
		return commonResponse(page, params, true, AlertResultDto.class, supplier);
	}
	
	private <T, U> ResponseEntity<DynamicSearchResultDto<T>> commonResponse(int page, MultiValueMap<String, String> params, boolean putActive, Class<T> classOutput, Supplier<DynamicSearchResult<U>> resultSupplier) {
		if (page < 0) return ResponseEntity.badRequest().build();
		
		params.remove("page");
		if(putActive) params.put("active", Arrays.asList("true"));
		
		DynamicSearchResult<U> results = resultSupplier.get();
		
		Page<T> pageResult = results.getPage().map(in -> simpleMapper.map(in, classOutput, mapperConfig.getMapping(), mapperConfig.getMapFunctions()));
		List<String> messages = parseMessages(results.getExceptions());
		
		if (results.getExceptions() == null || results.getExceptions().isEmpty()) {
			return ResponseEntity.ok(new DynamicSearchResultDto<T>(pageResult, messages));	
		} else {
			return ResponseEntity.badRequest().body(new DynamicSearchResultDto<T>(Page.empty(), messages));
		}
	}
	
	@GetMapping("/alerts/results/lastSuccess")
	public ResponseEntity<DateDto> lastSucess(@RequestParam Long alertId) {
		Date date = alertResultRepositoryExtended.findLastSuccessDateAlertResultByAlert(alertId);
		
		if (date != null) {
			return ResponseEntity.ok(new DateDto(date));
		} else {
			return ResponseEntity.noContent().build();
		}
	}
	
	@GetMapping("/alerts/results/lastIssue")
	public ResponseEntity<DateDto> lastIssue(@RequestParam Long alertId) {
		Date date = alertResultRepositoryExtended.findLastIssueDateAlertResultByAlert(alertId);
		
		if (date != null) {
			return ResponseEntity.ok(new DateDto(date));
		} else {
			return ResponseEntity.noContent().build();
		}
	}

	
	private List<String> parseMessages(List<Exception> exceptions) {
		List<String> messages = new ArrayList<>();
		
		if(exceptions != null) {
			exceptions.forEach(e -> {
				String msg;
				
				if (e instanceof AttributeNotFoundException) {
					msg = "Attribute " + e.getMessage() + " not found";
				} else if (e instanceof NumberFormatException) {
					msg = "NumberFormatException= " + e.getMessage();
				} else {
					msg = e.getMessage();
				}
				
				messages.add(msg);
			});
		}
		
		return messages;
	}

	@PostMapping("/alerts/results/{id}/resolve")
	public ResponseEntity<Object> resolveResult(@PathVariable Long id) {
		if (id == null || id < 0) return ResponseEntity.badRequest().build();
		Optional<AlertResult> r = alertResultRepositoryExtended.findById(id);
		if (r.isPresent()) {
			AlertResult alertResult = r.get();
			if (alertResult.isNeedsReview()) {
				alertResult.setNeedsReview(false);
				alertResultRepositoryExtended.saveAndFlush(alertResult);
			}
			return ResponseEntity.ok().build();
		} else {
			return ResponseEntity.notFound().build();
		}		
	}
	
}
