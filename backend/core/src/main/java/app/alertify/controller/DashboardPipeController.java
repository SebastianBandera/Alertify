package app.alertify.controller;

import java.util.UUID;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import app.alertify.config.AuthorizationPolicies;
import app.alertify.dashboard.*;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.repository.ProcedureExecutionRepository;
import app.alertify.pipes.api.PipeAcceptedResponse;
import app.alertify.pipes.api.PipeExecutionResponse;
import app.alertify.pipes.model.PipeStepType;
import app.alertify.pipes.service.PipeMapper;
import app.alertify.pipes.execution.PipeExecutionOrchestrator;
import app.alertify.logging.ApplicationEventLogger;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/dashboard/pipes")
@PreAuthorize(AuthorizationPolicies.ADMIN_OR_DASHBOARD)
public class DashboardPipeController {
    private final DashboardPipeCardService cards;
    private final ProcedureExecutionRepository procedures;
    private final PipeExecutionOrchestrator orchestrator;
    private final DashboardRunRateLimiter limiter;
    private final ApplicationEventLogger logger;

    public DashboardPipeController(DashboardPipeCardService cards, ProcedureExecutionRepository procedures, PipeExecutionOrchestrator orchestrator, DashboardRunRateLimiter limiter, ApplicationEventLogger logger) {
        this.cards = cards;
        this.procedures = procedures;
        this.orchestrator = orchestrator;
        this.limiter = limiter;
        this.logger = logger;
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("hasRole('DASHBOARD') and hasRole('DASHBOARD_PIPE_RUN')")
    public ResponseEntity<PipeAcceptedResponse> run(@PathVariable long id) {
        limiter.acquire(logger.currentUsername());
        return ResponseEntity.accepted().body(new PipeAcceptedResponse(orchestrator.triggerFromDashboard(id, logger.currentUsername())));
    }

    @GetMapping("/{id}/executions/{executionId}")
    @Transactional(readOnly = true)
    public PipeExecutionResponse execution(@PathVariable long id, @PathVariable UUID executionId) {
        return PipeMapper.execution(cards.execution(id, executionId));
    }

    @GetMapping("/{id}/executions/{executionId}/steps/{stepKey}")
    @Transactional(readOnly = true)
    public StepDetail step(@PathVariable long id, @PathVariable UUID executionId, @PathVariable String stepKey, Authentication authentication) {
        var execution = cards.execution(id, executionId);
        var step = execution.getSteps().stream().filter(value -> value.getStepKey().equals(stepKey)).findFirst().orElseThrow(() -> new ResourceNotFoundException("Pipe step not found"));
        if (step.getStepType() != PipeStepType.PROCEDURE || step.getResourceExecutionId() == null)
            return new StepDetail(null, false, step.getErrorCode());

        var child = procedures.findByExecutionId(step.getResourceExecutionId()).orElseThrow(() -> new ResourceNotFoundException("Procedure execution not found"));
        if (!executionId.equals(child.getParentPipeExecutionId()))
            throw new ResourceNotFoundException("Procedure execution not found");

        boolean admin = authentication.getAuthorities().stream().anyMatch(value -> value.getAuthority().equals("ROLE_ADMIN"));
        return new StepDetail(admin && !child.isResultRedacted() ? child.getResultJson() : null, child.isResultRedacted(), child.getErrorType());
    }

    public record StepDetail(JsonNode result, boolean resultRedacted, String errorType) { }
}
