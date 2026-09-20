package app.alertify.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertExecution;
import app.alertify.alerts.service.AlertMapper;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;

/** Assembles dashboard tiles: alert, latest execution, previous issue, look-back summary and in-progress marker. */
@Service
@Transactional(readOnly = true)
public class DashboardCardService {

    /** Length of the look-back window summarized on every tile. */
    public static final Duration HISTORY_WINDOW = Duration.ofDays(5);
    public static final int DEFAULT_PAGE_SIZE = 12;
    public static final int MAX_PAGE_SIZE = 50;

    private final AlertRepository alertRepository;
    private final AlertParameterValueRepository parameterValueRepository;
    private final AlertExecutionRepository executionRepository;
    private final DashboardExecutionQuery executionQuery;
    private final AlertExecutionRunningRegistry runningRegistry;

    public DashboardCardService(AlertRepository alertRepository, AlertParameterValueRepository parameterValueRepository, AlertExecutionRepository executionRepository, DashboardExecutionQuery executionQuery, AlertExecutionRunningRegistry runningRegistry) {
        this.alertRepository = alertRepository;
        this.parameterValueRepository = parameterValueRepository;
        this.executionRepository = executionRepository;
        this.executionQuery = executionQuery;
        this.runningRegistry = runningRegistry;
    }

    /** Pages alerts in a stable order (by id) so a snapshot loaded page by page never skips or repeats one. */
    public DashboardPageResponse page(int pageNumber, int pageSize) {
        int size = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        Page<Alert> alerts = alertRepository.findAll(PageRequest.of(Math.max(0, pageNumber), size, Sort.by("id")));
        return DashboardPageResponse.of(alerts, cards(alerts.getContent()));
    }

    public Optional<DashboardCardResponse> card(long alertId) {
        return alertRepository.findById(alertId).map(alert -> cards(List.of(alert)).getFirst());
    }

    private List<DashboardCardResponse> cards(List<Alert> alerts) {
        List<Long> alertIds = alerts.stream().map(Alert::getId).toList();
        Instant since = Instant.now().minus(HISTORY_WINDOW);
        Map<Long, Long> latestExecutionIds = executionQuery.latestExecutionIds(alertIds);
        Map<Long, Long> previousIssueIds = executionQuery.previousIssueExecutionIds(latestExecutionIds, since);
        Set<Long> executionIds = new HashSet<>(latestExecutionIds.values());
        executionIds.addAll(previousIssueIds.values());
        Map<Long, AlertExecutionResponse> executions = executionRepository.findAllById(executionIds).stream()
                .collect(Collectors.toMap(AlertExecution::getId, AlertMapper::toExecution));
        Map<Long, DashboardHistorySummaryResponse> summaries = executionQuery.historySummaries(latestExecutionIds.keySet(), since);
        return alerts.stream()
                .map(alert -> new DashboardCardResponse(
                        AlertMapper.toAlert(alert, parameterValueRepository.findAllByAlertIdOrdered(alert.getId())),
                        executions.get(latestExecutionIds.get(alert.getId())),
                        executions.get(previousIssueIds.get(alert.getId())),
                        summaries.get(alert.getId()),
                        runningRegistry.runningSince(alert.getId()).orElse(null)
                ))
                .toList();
    }
}
