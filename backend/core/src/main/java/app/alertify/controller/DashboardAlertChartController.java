package app.alertify.controller;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.service.AlertMapper;
import app.alertify.dashboard.DashboardHistoryWindow;
import app.alertify.jpa.repository.AlertExecutionRepository;

@RestController
@PreAuthorize(app.alertify.config.AuthorizationPolicies.ADMIN_OR_DASHBOARD)
public class DashboardAlertChartController {
    private static final int MAX_CHART_EXECUTIONS = 2_000;

    private final ChartHistory service;

    public DashboardAlertChartController(ChartHistory service) { this.service = service; }

    @GetMapping("/api/dashboard/alerts/{id}/chart-executions")
    public List<AlertExecutionResponse> chart(@PathVariable long id, @RequestParam Instant from, @RequestParam Instant to, @RequestParam(defaultValue = "" + MAX_CHART_EXECUTIONS) int limit, org.springframework.security.core.Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return service.history(id, from, to, limit, admin);
    }

    @Service
    public static class ChartHistory {
        private final AlertExecutionRepository executions;
        private final DashboardHistoryWindow window;

        public ChartHistory(AlertExecutionRepository executions, DashboardHistoryWindow window) {
            this.executions = executions;
            this.window = window;
        }

        @Transactional(readOnly = true)
        public List<AlertExecutionResponse> history(long alertId, Instant from, Instant to, int limit, boolean admin) {
            if (from.isAfter(to) || limit < 1)
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid chart window or limit");

            Instant now = Instant.now();
            Instant earliest = now.minus(java.time.Duration.ofDays(window.days()));
            Instant boundedFrom = from.isBefore(earliest) ? earliest : from;
            Instant boundedTo = to.isAfter(now) ? now : to;
            if (boundedFrom.isAfter(boundedTo))
                return List.of();

            return executions.chartHistory(alertId, boundedFrom, boundedTo,
                    PageRequest.of(0, Math.min(limit, MAX_CHART_EXECUTIONS), Sort.by(Sort.Direction.DESC, "finishedAt", "id")))
                    .stream().map(AlertMapper::toExecution)
                    .map(execution -> admin ? execution : execution.forViewer())
                    .sorted(Comparator.comparing(AlertExecutionResponse::finishedAt).thenComparing(AlertExecutionResponse::id)).toList();
        }
    }
}
