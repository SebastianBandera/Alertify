package app.alertify.service;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.stereotype.Service;

import app.alertify.controller.dto.summary.v1.Check;
import app.alertify.controller.dto.summary.v1.CheckError;
import app.alertify.controller.dto.summary.v1.CheckGroup;
import app.alertify.controller.dto.summary.v1.CheckGroups;
import app.alertify.entity.AlertResult;
import app.alertify.entity.GUIAlertGroup;
import app.alertify.entity.repositories.AlertResultRepository;
import app.alertify.entity.repositories.GUIAlertGroupRepository;

@Service
public class AlertService {

	@Autowired
	private AlertResultRepository alertResultsRepository;

	@Autowired
	private GUIAlertGroupRepository guiAlertGroupRepository;

	public CheckGroups summaryV1() {
		List<CheckGroup> checkGroupsList = new LinkedList<>();
		
		List<GUIAlertGroup> groups = guiAlertGroupRepository.findByActiveTrueEager();
		
		//N+1 ??
		groups.forEach(alertGroupGUI -> {
			List<AlertResult> alertResultsReview = alertResultsRepository.getAlertsResultByAlert(alertGroupGUI.getAlert(), true, PageRequest.of(0, Integer.MAX_VALUE).withSort(Sort.by(Order.desc("dateEnd"))));
			Date lastSuccess = alertResultsRepository.getAlertsResultByAlert(alertGroupGUI.getAlert(), false, PageRequest.of(0, 1).withSort(Sort.by(Order.desc("dateEnd")))).stream().map(item -> item.getDateEnd()).findFirst().orElse(null);
			
			List<Check> checks = new LinkedList<>();

			Check check = new Check();
			
			List<CheckError> errors = new LinkedList<>();
			
			alertResultsReview.forEach(resultsReview -> {
				CheckError checkError = new CheckError();
				checkError.setMessage(resultsReview.getResult());
				checkError.setStatus(resultsReview.getStatusResult().getName());
				checkError.setTime(resultsReview.getDateEnd());
				
				errors.add(checkError);
			});
			
			check.setErrors(errors);
			check.setLastSuccess(lastSuccess);
			check.setName(alertGroupGUI.getAlert().getName());
			check.setPeriod(alertGroupGUI.getAlert().getPeriodicity());
			
			checks.add(check);
			
			CheckGroup group = new CheckGroup();
			group.setName(alertGroupGUI.getName());
			group.setChecks(checks);
			
			checkGroupsList.add(group);
		});
		
		CheckGroups checkGroups = new CheckGroups();
		checkGroups.setCheckGroups(checkGroupsList);
		
		return checkGroups;
	}
	
	
}
