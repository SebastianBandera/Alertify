package app.alertify.alerts.service;

import java.time.Instant;
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
    private static final UUID UNUSED_EXECUTION_ID = new UUID(0, 0);
    private static final Instant UNUSED_TIME = Instant.EPOCH;

    private final AlertExecutionRepository executionRepository;
    private final ApplicationEventLogger eventLogger;

    public AlertExecutionQueryService(AlertExecutionRepository executionRepository, ApplicationEventLogger eventLogger) {
        this.executionRepository = executionRepository;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Page<AlertExecutionResponse> search(Long alertId, Long templateId, AlertExecutionStatus status, UUID executionId, Pageable pageable) {
        return search(alertId, templateId, status, executionId, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AlertExecutionResponse> search(Long alertId, Long templateId, AlertExecutionStatus status, UUID executionId, Instant from, Instant to, Pageable pageable) {
        if (from != null && to != null && from.isAfter(to))
            throw new IllegalArgumentException("Execution history lower time bound must not be after the upper bound");

        SearchValidation.validateSort(pageable, SORT_FIELDS);
        Page<AlertExecutionResponse> result = executionRepository.search(alertId != null, alertId == null ? 0L : alertId,
                templateId != null, templateId == null ? 0L : templateId,
                status != null, status == null ? AlertExecutionStatus.SUCCESS : status,
                executionId != null, executionId == null ? UNUSED_EXECUTION_ID : executionId,
                from != null, from == null ? UNUSED_TIME : from,
                to != null, to == null ? UNUSED_TIME : to, pageable).map(AlertMapper::toExecution);

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

        if (from != null)
            data.put("from", from);

        if (to != null)
            data.put("to", to);

        eventLogger.success("ALERT_EXECUTION_HISTORY_VIEWED", data);
        return result;
    }
}
