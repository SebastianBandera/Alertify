package app.alertify.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.dashboard.AlertIssueAcknowledgementResponse;
import app.alertify.dashboard.AlertIssueAcknowledgementService;
import jakarta.validation.constraints.Positive;

/**
 * Lets anyone who sees the dashboard mark an alert's persistent issues as
 * seen. Each user only reads and writes their own acknowledgements: the
 * subject always comes from the caller's token.
 */
@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("hasRole('ADMIN') or hasRole('DASHBOARD')")
@Validated
public class DashboardAcknowledgementController {

    private final AlertIssueAcknowledgementService service;

    public DashboardAcknowledgementController(AlertIssueAcknowledgementService service) {
        this.service = service;
    }

    @GetMapping("/acknowledgements")
    public List<AlertIssueAcknowledgementResponse> acknowledgements(@AuthenticationPrincipal Jwt jwt) {
        return service.forUser(jwt.getSubject());
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public AlertIssueAcknowledgementResponse acknowledge(@PathVariable @Positive Long id, @AuthenticationPrincipal Jwt jwt) {
        return service.acknowledge(id, jwt.getSubject());
    }
}
