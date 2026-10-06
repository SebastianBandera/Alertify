package app.alertify.ai.tools;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import app.alertify.ai.AiInvocationContextHolder;
import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AiToolSupport;
import app.alertify.ai.api.AiFilter;
import app.alertify.ai.api.AiPage;
import app.alertify.ai.api.AiPageRequest;
import app.alertify.ai.api.AiTextFile;
import app.alertify.alerts.api.AlertBindingOptionsResponse;
import app.alertify.alerts.api.AlertCreateRequest;
import app.alertify.alerts.api.AlertDeletionImpactResponse;
import app.alertify.alerts.api.AlertExecutionClosureRequest;
import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.api.AlertImportResult;
import app.alertify.alerts.api.AlertResponse;
import app.alertify.alerts.api.AlertStateResponse;
import app.alertify.alerts.api.AlertTemplateResponse;
import app.alertify.alerts.api.AlertUpdateRequest;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.service.AlertCatalogService;
import app.alertify.alerts.service.AlertCsvService;
import app.alertify.alerts.service.AlertExecutionClosureService;
import app.alertify.alerts.service.AlertExecutionQueryService;
import app.alertify.alerts.service.AlertManagementService;
import app.alertify.alerts.service.AlertTagService;
import app.alertify.alerts.service.ResourceResultObserverService;
import app.alertify.config.AuthorizationPolicies;
import app.alertify.configuration.api.TagCreateRequest;
import app.alertify.configuration.api.TagResponse;
import app.alertify.configuration.api.TagUpdateRequest;
import jakarta.validation.Valid;

@Component
@Validated
@PreAuthorize(AuthorizationPolicies.ADMIN)
public class AlertAiTools {

    private final AlertManagementService alerts;
    private final AlertCatalogService catalog;
    private final AlertCsvService csv;
    private final AlertExecutionQueryService executions;
    private final AlertExecutionClosureService closures;
    private final AlertTagService tags;
    private final ResourceResultObserverService observers;
    private final AiToolExecutor tools;
    private final AiToolSupport support;

    public AlertAiTools(AlertManagementService alerts, AlertCatalogService catalog, AlertCsvService csv,
            AlertExecutionQueryService executions, AlertExecutionClosureService closures, AlertTagService tags,
            ResourceResultObserverService observers, AiToolExecutor tools, AiToolSupport support) {
        this.alerts = alerts;
        this.catalog = catalog;
        this.csv = csv;
        this.executions = executions;
        this.closures = closures;
        this.tags = tags;
        this.observers = observers;
        this.tools = tools;
        this.support = support;
    }

    @Tool(name = "alertify_alert_search", description = "Search Alertify alert definitions with bounded pagination")
    public AiPage<AlertResponse> search(String name, Long templateId, List<Long> tagIds, boolean requireAllTags, @Valid AiPageRequest page) {
        return tools.execute("alertify_alert_search", () -> AiPage.from(alerts.search(name, templateId,
                tagIds == null ? java.util.Set.of() : new LinkedHashSet<>(tagIds), requireAllTags, page.pageable())));
    }

    @Tool(name = "alertify_alert_get", description = "Get one Alertify alert definition by numeric ID")
    public AlertResponse get(long id) { return tools.execute("alertify_alert_get", () -> alerts.get(id)); }

    @Tool(name = "alertify_alert_templates", description = "List available alert templates and their parameter schemas")
    public List<AlertTemplateResponse> templates() { return tools.execute("alertify_alert_templates", catalog::templates); }

    @Tool(name = "alertify_alert_binding_options", description = "List resources that may be referenced by alert parameters")
    public AlertBindingOptionsResponse bindingOptions() { return tools.execute("alertify_alert_binding_options", catalog::bindingOptions); }

    @Tool(name = "alertify_alert_observer_resources", description = "List resources available to an alert result observer")
    public List<ResourceResultObserverService.ResourceOption> observerResources(String kind) {
        return tools.execute("alertify_alert_observer_resources", () -> observers.options(kind));
    }

    @Tool(name = "alertify_alert_state", description = "Get the persisted runtime state for an alert")
    public AlertStateResponse state(long id) { return tools.execute("alertify_alert_state", () -> alerts.state(id)); }

    @Tool(name = "alertify_alert_deletion_impact", description = "Preview references that affect deleting an alert")
    public AlertDeletionImpactResponse deletionImpact(long id) {
        return tools.execute("alertify_alert_deletion_impact", () -> alerts.deletionImpact(id));
    }

    @Tool(name = "alertify_alert_create", description = "Create an alert using the same validation as the administration API")
    public AlertResponse create(@Valid AlertCreateRequest request) {
        return tools.execute("alertify_alert_create", () -> alerts.create(request));
    }

    @Tool(name = "alertify_alert_update", description = "Update an alert; the request must contain its current optimistic version")
    public AlertResponse update(long id, @Valid AlertUpdateRequest request) {
        return tools.execute("alertify_alert_update", () -> alerts.update(id, request));
    }

    @Tool(name = "alertify_alert_run", description = "Trigger an immediate alert execution")
    public void run(long id) { tools.execute("alertify_alert_run", () -> alerts.runNow(id)); }

    @Tool(name = "alertify_alert_delete", description = "Delete an alert using its current optimistic version")
    public void delete(long id, long version) { tools.execute("alertify_alert_delete", () -> alerts.delete(id, version)); }

    @Tool(name = "alertify_alert_export_csv", description = "Export alert definitions as a bounded UTF-8 CSV text file")
    public AiTextFile exportCsv() {
        return tools.execute("alertify_alert_export_csv", () -> support.csv("alertify-alerts.csv", csv.exportCsv()));
    }

    @Tool(name = "alertify_alert_import_csv", description = "Import alert definitions from a bounded UTF-8 CSV text file")
    public AlertImportResult importCsv(@Valid AiTextFile file) {
        return tools.execute("alertify_alert_import_csv", () -> csv.importCsv(support.csvUpload(file)));
    }

    @Tool(name = "alertify_alert_execution_search", description = "Search alert execution history")
    public AiPage<AlertExecutionResponse> executionSearch(Long alertId, Long templateId, AlertExecutionStatus status, UUID executionId, @Valid AiPageRequest page) {
        return tools.execute("alertify_alert_execution_search", () -> AiPage.from(
                executions.search(alertId, templateId, status, executionId, page.pageable())));
    }

    @Tool(name = "alertify_alert_execution_set_closed", description = "Close or reopen one WARN or ERROR alert execution")
    public AlertExecutionResponse setClosed(long executionDatabaseId, @Valid AlertExecutionClosureRequest request) {
        return tools.execute("alertify_alert_execution_set_closed", () -> {
            var context = AiInvocationContextHolder.current();
            return closures.change(executionDatabaseId, request.closed(), request.note(), context.userSubject(), context.username());
        });
    }

    @Tool(name = "alertify_alert_execution_closure_audit", description = "Read the immutable closure history for an alert execution")
    public List<AlertExecutionClosureService.ClosureAudit> closureAudit(long executionDatabaseId) {
        return tools.execute("alertify_alert_execution_closure_audit", () -> closures.audit(executionDatabaseId));
    }

    @Tool(name = "alertify_alert_tag_search", description = "Search tags scoped to alerts")
    public AiPage<TagResponse> tagSearch(List<AiFilter> filters, @Valid AiPageRequest page) {
        return tools.execute("alertify_alert_tag_search", () -> AiPage.from(tags.search(support.filters(filters), page.pageable())));
    }

    @Tool(name = "alertify_alert_tag_create", description = "Create a tag scoped to alerts")
    public TagResponse tagCreate(@Valid TagCreateRequest request) {
        return tools.execute("alertify_alert_tag_create", () -> tags.create(request));
    }

    @Tool(name = "alertify_alert_tag_update", description = "Update an alert tag using its current version")
    public TagResponse tagUpdate(long id, @Valid TagUpdateRequest request) {
        return tools.execute("alertify_alert_tag_update", () -> tags.update(id, request));
    }

    @Tool(name = "alertify_alert_tag_delete", description = "Delete an alert tag using its current version")
    public void tagDelete(long id, long version) {
        tools.execute("alertify_alert_tag_delete", () -> tags.delete(id, version));
    }
}
