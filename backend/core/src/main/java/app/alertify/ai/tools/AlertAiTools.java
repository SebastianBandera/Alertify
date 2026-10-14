package app.alertify.ai.tools;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import app.alertify.ai.AiInvocationContextHolder;
import app.alertify.ai.AiRefreshTarget;
import app.alertify.ai.AlertifyTool;
import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AiToolSupport;
import app.alertify.ai.api.AiAlertStateResponse;
import app.alertify.ai.api.AiAlertCreateRequest;
import app.alertify.ai.api.AiAlertUpdateRequest;
import app.alertify.ai.api.AiFilter;
import app.alertify.ai.api.AiPage;
import app.alertify.ai.api.AiPageRequest;
import app.alertify.ai.api.AiTextFile;
import app.alertify.ai.template.AiTemplateSource;
import app.alertify.ai.template.AiTemplateSourceService;
import app.alertify.alerts.api.AlertBindingOptionsResponse;
import app.alertify.alerts.api.AlertDeletionImpactResponse;
import app.alertify.alerts.api.AlertExecutionClosureRequest;
import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.api.AlertImportResult;
import app.alertify.alerts.api.AlertResponse;
import app.alertify.alerts.api.AlertTemplateResponse;
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
import app.alertify.dashboard.DashboardCardService;
import jakarta.validation.Valid;

@Component
@Validated
@PreAuthorize(AuthorizationPolicies.ADMIN)
public class AlertAiTools {

    private final AlertManagementService alerts;
    private final DashboardCardService dashboardCards;
    private final AlertCatalogService catalog;
    private final AlertCsvService csv;
    private final AlertExecutionQueryService executions;
    private final AlertExecutionClosureService closures;
    private final AlertTagService tags;
    private final ResourceResultObserverService observers;
    private final AiTemplateSourceService templateSources;
    private final AiToolExecutor tools;
    private final AiToolSupport support;

    public AlertAiTools(AlertManagementService alerts, DashboardCardService dashboardCards, AlertCatalogService catalog, AlertCsvService csv,
            AlertExecutionQueryService executions, AlertExecutionClosureService closures, AlertTagService tags,
            ResourceResultObserverService observers, AiTemplateSourceService templateSources, AiToolExecutor tools,
            AiToolSupport support) {
        this.alerts = alerts;
        this.dashboardCards = dashboardCards;
        this.catalog = catalog;
        this.csv = csv;
        this.executions = executions;
        this.closures = closures;
        this.tags = tags;
        this.observers = observers;
        this.templateSources = templateSources;
        this.tools = tools;
        this.support = support;
    }

    @AlertifyTool(name = "alertify_alert_search", description = "Search Alertify alert definitions. Omit templateId or use 0 for no template filter. Omit pagination unless the user requests a specific order; its default is page 0, size 20, sorted by updatedAt DESC (newest first).", readOnly = true)
    public AiPage<AlertResponse> search(@ToolParam(description = "Optional case-insensitive name fragment", required = false) String name, @ToolParam(description = "Optional positive template ID; omit or use 0 for every template", required = false) Long templateId, @ToolParam(description = "Optional alert tag IDs", required = false) List<Long> tagIds, @ToolParam(description = "Optionally require every tag instead of any tag; defaults to false", required = false) Boolean requireAllTags, @ToolParam(description = "Optional bounded pagination; defaults to page 0, size 20, updatedAt DESC", required = false) @Valid AiPageRequest page) {
        if (templateId != null && templateId < 0)
            throw new IllegalArgumentException("Template ID must be zero or greater");

        Long templateFilter = templateId == null || templateId == 0 ? null : templateId;
        AiPageRequest pagination = page == null ? new AiPageRequest(0, 20, "updatedAt", Sort.Direction.DESC) : page;
        return tools.execute("alertify_alert_search", () -> AiPage.from(alerts.search(name, templateFilter, tagIds == null ? java.util.Set.of() : new LinkedHashSet<>(tagIds), Boolean.TRUE.equals(requireAllTags), pagination.pageable())));
    }

    @AlertifyTool(name = "alertify_alert_get", description = "Get one Alertify alert definition by numeric ID", readOnly = true)
    public AlertResponse get(long id) { return tools.execute("alertify_alert_get", () -> alerts.get(id)); }

    @AlertifyTool(name = "alertify_alert_templates", description = "List available alert templates and their parameter schemas. Each result includes the fully qualified templateKey accepted by alertify_alert_template_source. Empty allowedSecretValueTypes or allowedConfigurationValueTypes means no additional declared value-type restriction, not a prohibited source. Non-empty lists further restrict compatible types. Always check bindingAllowed, allowedSources, Java-type compatibility and required writability; binary and structured type requirements still apply.", readOnly = true)
    public List<AlertTemplateResponse> templates() { return tools.execute("alertify_alert_templates", catalog::templates); }

    @AlertifyTool(name = "alertify_alert_template_source", description = "Return the exact Java source of one registered alert template. First use alertify_alert_templates to discover its fully qualified templateKey.", readOnly = true, idempotentHint = true)
    public AiTemplateSource templateSource(@ToolParam(description = "Exact fully qualified templateKey returned by alertify_alert_templates") String templateKey) {
        return tools.execute("alertify_alert_template_source", () -> templateSources.alert(templateKey));
    }

    @AlertifyTool(name = "alertify_alert_binding_options", description = "List global resource metadata that alert parameters may reference, without protected values. These candidates are not compatibility-filtered for a particular parameter. Empty allowedSecretValueTypes or allowedConfigurationValueTypes in its template means no additional declared value-type restriction, not a prohibited source. Non-empty lists further restrict compatible types. Always check bindingAllowed, allowedSources, Java-type compatibility and required writability; binary and structured type requirements still apply. Do not read protected values or execute resources to check metadata compatibility.", readOnly = true)
    public AlertBindingOptionsResponse bindingOptions() { return tools.execute("alertify_alert_binding_options", catalog::bindingOptions); }

    @AlertifyTool(name = "alertify_alert_observer_resources", description = "List resources available to an alert result observer", readOnly = true)
    public List<ResourceResultObserverService.ResourceOption> observerResources(String kind) {
        return tools.execute("alertify_alert_observer_resources", () -> observers.options(kind));
    }

    @AlertifyTool(name = "alertify_alert_state", description = "Get the complete current dashboard card and persisted runtime state for an alert. Use dashboardCard.lastExecution for the actual current result, including statusMessage details such as HTTP status codes, startedAt, finishedAt and durations. The card also includes previousIssue, history, runningSince and the configured history window. persistedState is template-specific runtime state and may contain less detail than the latest execution.", readOnly = true)
    public AiAlertStateResponse state(long id) {
        return tools.execute("alertify_alert_state", () -> new AiAlertStateResponse(alerts.state(id),
                dashboardCards.card(id).orElseThrow(() -> new app.alertify.api.error.ResourceNotFoundException("Alert " + id + " was not found"))));
    }

    @AlertifyTool(name = "alertify_alert_deletion_impact", description = "Preview references that affect deleting an alert", readOnly = true)
    public AlertDeletionImpactResponse deletionImpact(long id) {
        return tools.execute("alertify_alert_deletion_impact", () -> alerts.deletionImpact(id));
    }

    @AlertifyTool(name = "alertify_alert_create", description = "Create an alert using the same validation as the administration API. Read the selected template's parameter definitions; include each parameterKey at most once and match every requested source to its text value or positive existing compatible reference ID. Use null for inactive fields; legacy null or 0 reference placeholders are ignored only for unselected sources.", readOnly = false, refreshTargets = { AiRefreshTarget.ALERTS, AiRefreshTarget.DASHBOARD })
    public AlertResponse create(@Valid AiAlertCreateRequest request) {
        return tools.execute("alertify_alert_create", () -> alerts.create(request.toRequest()));
    }

    @AlertifyTool(name = "alertify_alert_update", description = "Update an alert; first read the complete current alert and preserve every unchanged field. The request must contain its current optimistic version. Parameters is a complete replacement list: replace the requested entry by parameterKey exactly once, preserve all other parameters and never append a duplicate. Omitted parameters may reset to defaults or be removed. Match the requested source to its active text value or positive existing compatible reference ID; use null for inactive fields, with legacy null or 0 placeholders ignored only for unselected references. Set persistentIssues to null unless the user explicitly requested a change to issue persistence; smart execution, enabled and parameter bindings do not authorize changing that option. After success, read the alert again and compare the requested and preserved fields before reporting verified completion.", readOnly = false, refreshTargets = { AiRefreshTarget.ALERTS, AiRefreshTarget.DASHBOARD })
    public AlertResponse update(long id, @Valid AiAlertUpdateRequest request) {
        return tools.execute("alertify_alert_update", () -> alerts.update(id, request.toRequest()));
    }

    @AlertifyTool(name = "alertify_alert_run", description = "Trigger an immediate alert execution", readOnly = false, openWorldHint = true, refreshTargets = { AiRefreshTarget.ALERTS, AiRefreshTarget.DASHBOARD })
    public void run(long id) { tools.execute("alertify_alert_run", () -> alerts.runNow(id)); }

    @AlertifyTool(name = "alertify_alert_delete", description = "Delete an alert using its current optimistic version", readOnly = false, destructiveHint = true, refreshTargets = { AiRefreshTarget.ALERTS, AiRefreshTarget.DASHBOARD })
    public void delete(long id, long version) { tools.execute("alertify_alert_delete", () -> alerts.delete(id, version)); }

    @AlertifyTool(name = "alertify_alert_export_csv", description = "Export alert definitions as a bounded UTF-8 CSV text file", readOnly = true)
    public AiTextFile exportCsv() {
        return tools.execute("alertify_alert_export_csv", () -> support.csv("alertify-alerts.csv", csv.exportCsv()));
    }

    @AlertifyTool(name = "alertify_alert_import_csv", description = "Import alert definitions from a bounded UTF-8 CSV text file", readOnly = false, refreshTargets = { AiRefreshTarget.ALERTS, AiRefreshTarget.DASHBOARD })
    public AlertImportResult importCsv(@Valid AiTextFile file) {
        return tools.execute("alertify_alert_import_csv", () -> csv.importCsv(support.csvUpload(file)));
    }

    @AlertifyTool(name = "alertify_alert_execution_search", description = "Search complete alert execution history, including statusMessage details and execution timestamps. Use from and to to answer questions about a time period; both filter finishedAt and use ISO-8601 instants. Omit status to find multiple statuses such as WARN and ERROR in one search. All filters and pagination are optional. Zero IDs and a blank executionId mean no filter. Pagination defaults to page 0, size 20, finishedAt DESC.", readOnly = true)
    public AiPage<AlertExecutionResponse> executionSearch(@ToolParam(description = "Optional positive alert ID; omit or use 0 for every alert", required = false) Long alertId, @ToolParam(description = "Optional positive template ID; omit or use 0 for every template", required = false) Long templateId, @ToolParam(description = "Optional execution result status", required = false) AlertExecutionStatus status, @ToolParam(description = "Optional execution UUID; omit or use an empty string for every execution", required = false) String executionId, @ToolParam(description = "Optional inclusive lower bound for finishedAt as an ISO-8601 instant", required = false) Instant from, @ToolParam(description = "Optional inclusive upper bound for finishedAt as an ISO-8601 instant", required = false) Instant to, @ToolParam(description = "Optional bounded pagination; defaults to page 0, size 20, finishedAt DESC", required = false) @Valid AiPageRequest page) {
        if (alertId != null && alertId < 0)
            throw new IllegalArgumentException("Alert ID must be zero or greater");

        if (templateId != null && templateId < 0)
            throw new IllegalArgumentException("Template ID must be zero or greater");

        Long alertFilter = alertId == null || alertId == 0 ? null : alertId;
        Long templateFilter = templateId == null || templateId == 0 ? null : templateId;
        UUID executionFilter = executionId == null || executionId.isBlank() ? null : UUID.fromString(executionId);
        AiPageRequest pagination = page == null ? new AiPageRequest(0, 20, "finishedAt", Sort.Direction.DESC) : page;
        return tools.execute("alertify_alert_execution_search", () -> AiPage.from(
                executions.search(alertFilter, templateFilter, status, executionFilter, from, to, pagination.pageable())));
    }

    @AlertifyTool(name = "alertify_alert_execution_set_closed", description = "Close or reopen one WARN or ERROR alert execution", readOnly = false, refreshTargets = { AiRefreshTarget.ALERTS, AiRefreshTarget.DASHBOARD })
    public AlertExecutionResponse setClosed(long executionDatabaseId, @Valid AlertExecutionClosureRequest request) {
        return tools.execute("alertify_alert_execution_set_closed", () -> {
            var context = AiInvocationContextHolder.current();
            return closures.change(executionDatabaseId, request.closed(), request.note(), context.userSubject(), context.username());
        });
    }

    @AlertifyTool(name = "alertify_alert_execution_closure_audit", description = "Read the immutable closure history for an alert execution", readOnly = true)
    public List<AlertExecutionClosureService.ClosureAudit> closureAudit(long executionDatabaseId) {
        return tools.execute("alertify_alert_execution_closure_audit", () -> closures.audit(executionDatabaseId));
    }

    @AlertifyTool(name = "alertify_alert_tag_search", description = "Search tags scoped to alerts", readOnly = true)
    public AiPage<TagResponse> tagSearch(List<AiFilter> filters, @Valid AiPageRequest page) {
        return tools.execute("alertify_alert_tag_search", () -> AiPage.from(tags.search(support.filters(filters), page.pageable())));
    }

    @AlertifyTool(name = "alertify_alert_tag_create", description = "Create a tag scoped to alerts", readOnly = false, refreshTargets = { AiRefreshTarget.ALERTS, AiRefreshTarget.DASHBOARD })
    public TagResponse tagCreate(@Valid TagCreateRequest request) {
        return tools.execute("alertify_alert_tag_create", () -> tags.create(request));
    }

    @AlertifyTool(name = "alertify_alert_tag_update", description = "Update an alert tag using its current version", readOnly = false, refreshTargets = { AiRefreshTarget.ALERTS, AiRefreshTarget.DASHBOARD })
    public TagResponse tagUpdate(long id, @Valid TagUpdateRequest request) {
        return tools.execute("alertify_alert_tag_update", () -> tags.update(id, request));
    }

    @AlertifyTool(name = "alertify_alert_tag_delete", description = "Delete an alert tag using its current version", readOnly = false, destructiveHint = true, refreshTargets = { AiRefreshTarget.ALERTS, AiRefreshTarget.DASHBOARD })
    public void tagDelete(long id, long version) {
        tools.execute("alertify_alert_tag_delete", () -> tags.delete(id, version));
    }
}
