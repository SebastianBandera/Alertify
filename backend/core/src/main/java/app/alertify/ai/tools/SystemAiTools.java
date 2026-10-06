package app.alertify.ai.tools;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AiToolSupport;
import app.alertify.ai.api.AiFilter;
import app.alertify.ai.api.AiPage;
import app.alertify.ai.api.AiPageRequest;
import app.alertify.config.AuthorizationPolicies;
import app.alertify.grpc.api.WorkerActivityHistoryResponse;
import app.alertify.grpc.api.WorkerNodeStatusResponse;
import app.alertify.grpc.discovery.WorkerActivityHistoryService;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.logging.ApplicationLogService;
import app.alertify.logging.api.ApplicationLogResponse;
import app.alertify.system.SystemStatusService;
import app.alertify.system.api.SystemStatusSummaryResponse;
import jakarta.validation.Valid;

@Component
@PreAuthorize(AuthorizationPolicies.ADMIN)
public class SystemAiTools {

    private final SystemStatusService system;
    private final WorkerStatusService workers;
    private final WorkerActivityHistoryService workerHistory;
    private final ApplicationLogService logs;
    private final AiToolExecutor tools;
    private final AiToolSupport support;

    public SystemAiTools(SystemStatusService system, WorkerStatusService workers,
            WorkerActivityHistoryService workerHistory, ApplicationLogService logs,
            AiToolExecutor tools, AiToolSupport support) {
        this.system = system;
        this.workers = workers;
        this.workerHistory = workerHistory;
        this.logs = logs;
        this.tools = tools;
        this.support = support;
    }

    @Tool(name = "alertify_system_status", description = "Read the current Alertify system status summary")
    public SystemStatusSummaryResponse status() {
        return tools.execute("alertify_system_status", system::summary);
    }

    @Tool(name = "alertify_worker_status", description = "List current worker availability and capabilities")
    public List<WorkerNodeStatusResponse> workerStatus() {
        return tools.execute("alertify_worker_status", workers::status);
    }

    @Tool(name = "alertify_worker_activity_history", description = "Read recent aggregate worker activity")
    public WorkerActivityHistoryResponse workerActivityHistory() {
        return tools.execute("alertify_worker_activity_history", workerHistory::history);
    }

    @Tool(name = "alertify_audit_log_search", description = "Search immutable application audit events with bounded pagination")
    public AiPage<ApplicationLogResponse> auditLogs(List<AiFilter> filters, @Valid AiPageRequest page) {
        return tools.execute("alertify_audit_log_search",
                () -> AiPage.from(logs.search(support.filters(filters), page.pageable())));
    }

    @Tool(name = "alertify_audit_event_codes", description = "List known application audit event codes")
    public List<String> auditEventCodes() {
        return tools.execute("alertify_audit_event_codes", logs::eventCodes);
    }
}
