package app.alertify.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.service.AlertExecutionQueryService;

@RestController
@RequestMapping("/api/alert-executions")
@PreAuthorize("hasRole('ADMIN')")
public class AlertExecutionController {

    private final AlertExecutionQueryService service;

    public AlertExecutionController(AlertExecutionQueryService service) {
        this.service = service;
    }

    @GetMapping
    public Page<AlertExecutionResponse> search(@RequestParam(required = false) Long alertId, @RequestParam(required = false) AlertExecutionStatus status, @PageableDefault(size = 20, sort = "startedAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        return service.search(alertId, status, pageable);
    }
}
