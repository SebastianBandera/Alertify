package app.alertify.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.alerts.service.AlertManagementService;
import app.alertify.dashboard.DashboardRunRateLimiter;
import app.alertify.logging.ApplicationEventLogger;

/**
 * On-demand runs for dashboard viewers holding DASHBOARD_RUN. Kept apart from
 * the administration API so viewers never reach the rest of /api/alerts.
 */
@RestController
@RequestMapping("/api/dashboard/alerts")
@PreAuthorize("hasRole('DASHBOARD') and hasRole('DASHBOARD_RUN')")
public class DashboardAlertRunController {

    private final AlertManagementService service;
    private final DashboardRunRateLimiter rateLimiter;
    private final ApplicationEventLogger eventLogger;

    public DashboardAlertRunController(AlertManagementService service, DashboardRunRateLimiter rateLimiter, ApplicationEventLogger eventLogger) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.eventLogger = eventLogger;
    }

    // The slot is taken before looking at the alert, so every attempt counts against the limit.
    @PostMapping("/{id}/run")
    public ResponseEntity<Void> runNow(@PathVariable Long id) {
        rateLimiter.acquire(eventLogger.currentUsername());
        service.runFromDashboard(id);
        return ResponseEntity.accepted().build();
    }
}
