package app.alertify.alerts.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.configuration.service.SearchValidation;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.logging.ApplicationEventLogger;

@Service
public class AlertExecutionQueryService {

    private static final Set<String> SORT_FIELDS = Set.of("id", "status", "startedAt", "finishedAt");

    private final AlertExecutionRepository executionRepository;
    private final ApplicationEventLogger eventLogger;

    public AlertExecutionQueryService(AlertExecutionRepository executionRepository, ApplicationEventLogger eventLogger) {
        this.executionRepository = executionRepository;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Page<AlertExecutionResponse> search(Long alertId, Long templateId, AlertExecutionStatus status, UUID executionId, Pageable pageable) {
        SearchValidation.validateSort(pageable, SORT_FIELDS);
        Page<AlertExecutionResponse> result = executionRepository.search(alertId, templateId, status, executionId, pageable).map(AlertMapper::toExecution);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.getNumber());
        data.put("size", result.getSize());
        data.put("totalElements", result.getTotalElements());
        if (alertId != null)
            data.put("alertId", alertId);

        if (templateId != null)
            data.put("templateId", templateId);

        if (status != null)
            data.put("status", status.name());

        if (executionId != null)
            data.put("executionId", executionId);

        eventLogger.success("ALERT_EXECUTION_HISTORY_VIEWED", data);
        return result;
    }
}
