package app.alertify.controller;

import java.util.UUID;

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
@PreAuthorize(app.alertify.config.AuthorizationPolicies.ADMIN)
public class AlertExecutionController {

    private final AlertExecutionQueryService service;
    private final app.alertify.alerts.service.AlertExecutionClosureService closureService;

    public AlertExecutionController(AlertExecutionQueryService service, app.alertify.alerts.service.AlertExecutionClosureService closureService) {
        this.service = service;
        this.closureService = closureService;
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/closure")
    public AlertExecutionResponse closure(@org.springframework.web.bind.annotation.PathVariable long id, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody app.alertify.alerts.api.AlertExecutionClosureRequest request, @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        String actor = jwt.getClaimAsString("preferred_username");
        return closureService.change(id, request.closed(), request.note(), jwt.getSubject(), actor == null ? jwt.getSubject() : actor);
    }

    @GetMapping("/{id}/closure-audit")
    public java.util.List<app.alertify.alerts.service.AlertExecutionClosureService.ClosureAudit> closureAudit(@org.springframework.web.bind.annotation.PathVariable long id) {
        return closureService.audit(id);
    }

    @GetMapping
    public Page<AlertExecutionResponse> search(@RequestParam(required = false) Long alertId, @RequestParam(required = false) Long templateId, @RequestParam(required = false) AlertExecutionStatus status, @RequestParam(required = false) UUID executionId, @PageableDefault(size = 20, sort = "startedAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        return service.search(alertId, templateId, status, executionId, pageable);
    }
}
