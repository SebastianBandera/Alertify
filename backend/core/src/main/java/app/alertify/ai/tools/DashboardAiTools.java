package app.alertify.ai.tools;

import java.time.Instant;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import app.alertify.ai.AiInvocationContextHolder;
import app.alertify.ai.AiToolExecutor;
import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.service.AlertManagementService;
import app.alertify.config.AuthorizationPolicies;
import app.alertify.controller.DashboardAlertChartController.ChartHistory;
import app.alertify.dashboard.AlertIssueAcknowledgementResponse;
import app.alertify.dashboard.AlertIssueAcknowledgementService;
import app.alertify.dashboard.DashboardRunRateLimiter;

@Component
public class DashboardAiTools {

    private final AlertIssueAcknowledgementService acknowledgements;
    private final ChartHistory charts;
    private final DashboardRunRateLimiter rateLimiter;
    private final AlertManagementService alerts;
    private final AiToolExecutor tools;

    public DashboardAiTools(AlertIssueAcknowledgementService acknowledgements, ChartHistory charts,
            DashboardRunRateLimiter rateLimiter, AlertManagementService alerts, AiToolExecutor tools) {
        this.acknowledgements = acknowledgements;
        this.charts = charts;
        this.rateLimiter = rateLimiter;
        this.alerts = alerts;
        this.tools = tools;
    }

    @Tool(name = "alertify_dashboard_acknowledgements", description = "List persistent alert issues acknowledged by the current user")
    @PreAuthorize(AuthorizationPolicies.ADMIN_OR_DASHBOARD)
    public List<AlertIssueAcknowledgementResponse> acknowledgements() {
        return tools.execute("alertify_dashboard_acknowledgements",
                () -> acknowledgements.forUser(AiInvocationContextHolder.current().userSubject()));
    }

    @Tool(name = "alertify_dashboard_acknowledge_alert", description = "Acknowledge one alert's persistent issues for the current user")
    @PreAuthorize(AuthorizationPolicies.ADMIN_OR_DASHBOARD)
    public AlertIssueAcknowledgementResponse acknowledge(long alertId) {
        return tools.execute("alertify_dashboard_acknowledge_alert",
                () -> acknowledgements.acknowledge(alertId, AiInvocationContextHolder.current().userSubject()));
    }

    @Tool(name = "alertify_dashboard_alert_chart", description = "Read a bounded alert execution window suitable for chart analysis")
    @PreAuthorize(AuthorizationPolicies.ADMIN_OR_DASHBOARD)
    public List<AlertExecutionResponse> chart(long alertId, Instant from, Instant to, int limit) {
        if (limit < 1 || limit > 50)
            throw new IllegalArgumentException("AI chart limit must be between 1 and 50");

        return tools.execute("alertify_dashboard_alert_chart", () -> {
            boolean admin = AiInvocationContextHolder.current().authorities().contains("ROLE_ADMIN");
            return charts.history(alertId, from, to, limit, admin);
        });
    }

    @Tool(name = "alertify_dashboard_alert_run", description = "Run one alert with the current dashboard user's normal rate limit")
    @PreAuthorize(AuthorizationPolicies.DASHBOARD_RUN)
    public void run(long alertId) {
        tools.execute("alertify_dashboard_alert_run", () -> {
            rateLimiter.acquire(AiInvocationContextHolder.current().username());
            alerts.runFromDashboard(alertId);
        });
    }
}
