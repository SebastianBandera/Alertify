package app.alertify.ai.tools;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import app.alertify.ai.AiRefreshTarget;
import app.alertify.ai.AlertifyTool;
import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AiToolSupport;
import app.alertify.ai.api.AiFilter;
import app.alertify.ai.api.AiPage;
import app.alertify.ai.api.AiPageRequest;
import app.alertify.ai.api.AiProcedureCreateRequest;
import app.alertify.ai.api.AiProcedureUpdateRequest;
import app.alertify.ai.api.AiTextFile;
import app.alertify.ai.template.AiTemplateSource;
import app.alertify.ai.template.AiTemplateSourceService;
import app.alertify.config.AuthorizationPolicies;
import app.alertify.configuration.api.TagCreateRequest;
import app.alertify.configuration.api.TagResponse;
import app.alertify.configuration.api.TagUpdateRequest;
import app.alertify.procedures.api.ProcedureBindingOptionsResponse;
import app.alertify.procedures.api.ProcedureDeletionImpactResponse;
import app.alertify.procedures.api.ProcedureExecutionResponse;
import app.alertify.procedures.api.ProcedureImportResult;
import app.alertify.procedures.api.ProcedureResponse;
import app.alertify.procedures.api.ProcedureTemplateResponse;
import app.alertify.procedures.execution.ProcedureExecutionStatus;
import app.alertify.procedures.service.ProcedureCatalogService;
import app.alertify.procedures.service.ProcedureCsvService;
import app.alertify.procedures.service.ProcedureExecutionQueryService;
import app.alertify.procedures.service.ProcedureManagementService;
import app.alertify.procedures.service.ProcedureTagService;
import jakarta.validation.Valid;

@Component
@Validated
@PreAuthorize(AuthorizationPolicies.ADMIN)
public class ProcedureAiTools {

    private final ProcedureManagementService procedures;
    private final ProcedureCatalogService catalog;
    private final ProcedureCsvService csv;
    private final ProcedureExecutionQueryService executions;
    private final ProcedureTagService tags;
    private final AiTemplateSourceService templateSources;
    private final AiToolExecutor tools;
    private final AiToolSupport support;

    public ProcedureAiTools(ProcedureManagementService procedures, ProcedureCatalogService catalog,
            ProcedureCsvService csv, ProcedureExecutionQueryService executions, ProcedureTagService tags,
            AiTemplateSourceService templateSources, AiToolExecutor tools, AiToolSupport support) {
        this.procedures = procedures;
        this.catalog = catalog;
        this.csv = csv;
        this.executions = executions;
        this.tags = tags;
        this.templateSources = templateSources;
        this.tools = tools;
        this.support = support;
    }

    @AlertifyTool(name = "alertify_procedure_search", description = "Search procedure definitions. Omit templateId or use 0 for no template filter. Pagination defaults to page 0, size 20, updatedAt DESC. An empty tagIds list means no tag filter, not resources without tags; inspect the returned tags and every page for that request.", readOnly = true)
    public AiPage<ProcedureResponse> search(@ToolParam(description = "Optional case-insensitive name fragment", required = false) String name, @ToolParam(description = "Optional positive template ID; omit or use 0 for every template", required = false) Long templateId, @ToolParam(description = "Optional procedure tag IDs; empty means no tag filter", required = false) List<Long> tagIds, @ToolParam(description = "Require every tag instead of any tag; defaults to false", required = false) Boolean requireAllTags, @ToolParam(description = "Optional bounded pagination; defaults to page 0, size 20, updatedAt DESC", required = false) @Valid AiPageRequest page) {
        if (templateId != null && templateId < 0)
            throw new IllegalArgumentException("Template ID must be zero or greater");

        Long templateFilter = templateId == null || templateId == 0 ? null : templateId;
        AiPageRequest pagination = page == null ? new AiPageRequest(0, 20, "updatedAt", Sort.Direction.DESC) : page;
        return tools.execute("alertify_procedure_search", () -> AiPage.from(procedures.search(name, templateFilter,
                tagIds == null ? java.util.Set.of() : new LinkedHashSet<>(tagIds), Boolean.TRUE.equals(requireAllTags), pagination.pageable())));
    }

    @AlertifyTool(name = "alertify_procedure_templates", description = "List procedure templates, parameters, outputs and sensitive-result flags. Each result includes the fully qualified templateKey accepted by alertify_procedure_template_source. Empty allowedSecretValueTypes or allowedConfigurationValueTypes means no additional declared value-type restriction, not a prohibited source. Non-empty lists further restrict compatible types. Always check bindingAllowed, allowedSources, Java-type compatibility and required writability; binary and structured type requirements still apply.", readOnly = true)
    public List<ProcedureTemplateResponse> templates() {
        return tools.execute("alertify_procedure_templates", catalog::templates);
    }

    @AlertifyTool(name = "alertify_procedure_template_get", description = "Get exactly one procedure template definition by positive templateId or exact fully qualified templateKey. Provide at least one selector; if both are given they must identify the same template. Prefer this focused metadata after selecting a template. Parameter definitions use key; copy that exact key to parameterKey in create/update payloads, once per parameter. Empty allowedSecretValueTypes or allowedConfigurationValueTypes means no additional declared value-type restriction, not a prohibited source. Still check bindingAllowed, allowedSources, Java-type compatibility and required writability. Executable behavior claims require alertify_procedure_template_source for the same templateKey.", readOnly = true, idempotentHint = true)
    public ProcedureTemplateResponse template(@ToolParam(description = "Positive ID of the selected template; omit when selecting by templateKey", required = false) Long templateId, @ToolParam(description = "Exact fully qualified templateKey; omit when selecting by ID, or supply to check identity", required = false) String templateKey) {
        return tools.execute("alertify_procedure_template_get", () -> catalog.template(templateId, templateKey));
    }

    @AlertifyTool(name = "alertify_procedure_template_source", description = "Return the exact Java source of one registered procedure template. First use alertify_procedure_templates to discover its fully qualified templateKey.", readOnly = true, idempotentHint = true)
    public AiTemplateSource templateSource(@ToolParam(description = "Exact fully qualified templateKey returned by alertify_procedure_templates") String templateKey) {
        return tools.execute("alertify_procedure_template_source", () -> templateSources.procedure(templateKey));
    }

    @AlertifyTool(name = "alertify_procedure_binding_options", description = "List global resource metadata that procedure parameters may reference, without protected values. These candidates are not compatibility-filtered for a particular parameter. Empty allowedSecretValueTypes or allowedConfigurationValueTypes in its template means no additional declared value-type restriction, not a prohibited source. Non-empty lists further restrict compatible types. Always check bindingAllowed, allowedSources, Java-type compatibility and required writability; binary and structured type requirements still apply. Do not read protected values or execute resources to check metadata compatibility.", readOnly = true)
    public ProcedureBindingOptionsResponse bindingOptions() {
        return tools.execute("alertify_procedure_binding_options", catalog::bindingOptions);
    }

    @AlertifyTool(name = "alertify_procedure_deletion_impact", description = "Preview references that affect deleting a procedure", readOnly = true)
    public ProcedureDeletionImpactResponse deletionImpact(long id) {
        return tools.execute("alertify_procedure_deletion_impact", () -> procedures.deletionImpact(id));
    }

    @AlertifyTool(name = "alertify_procedure_create", description = "Create a procedure using administration validation. Read the selected template's parameter definitions; include each parameterKey at most once and match every requested source to its text value or positive existing compatible reference ID. Use null for inactive fields; legacy null or 0 reference placeholders are ignored only for unselected sources.", readOnly = false, refreshTargets = AiRefreshTarget.PROCEDURES)
    public ProcedureResponse create(@Valid AiProcedureCreateRequest request) {
        return tools.execute("alertify_procedure_create", () -> procedures.create(request.toRequest()));
    }

    @AlertifyTool(name = "alertify_procedure_update", description = "Update a procedure using its current optimistic version; first read its complete current definition and the selected template's parameters. Parameters is a complete replacement list: replace the requested entry by parameterKey exactly once, preserve all other parameters and fields, and never append a duplicate. Omitted parameters may reset to defaults or be removed. Match the requested source to its active text value or positive existing compatible reference ID. Use null for inactive fields; legacy null or 0 reference placeholders are ignored only for unselected sources. After success, read the definition again and compare the requested and preserved fields before reporting verified completion.", readOnly = false, refreshTargets = AiRefreshTarget.PROCEDURES)
    public ProcedureResponse update(long id, @Valid AiProcedureUpdateRequest request) {
        return tools.execute("alertify_procedure_update", () -> procedures.update(id, request.toRequest()));
    }

    @AlertifyTool(name = "alertify_procedure_run", description = "Trigger an immediate procedure execution", readOnly = false, openWorldHint = true, refreshTargets = AiRefreshTarget.PROCEDURES)
    public void run(long id) { tools.execute("alertify_procedure_run", () -> procedures.runNow(id)); }

    @AlertifyTool(name = "alertify_procedure_delete", description = "Delete a procedure using its current optimistic version", readOnly = false, destructiveHint = true, refreshTargets = AiRefreshTarget.PROCEDURES)
    public void delete(long id, long version) { tools.execute("alertify_procedure_delete", () -> procedures.delete(id, version)); }

    @AlertifyTool(name = "alertify_procedure_export_csv", description = "Export procedures as a bounded UTF-8 CSV text file", readOnly = true)
    public AiTextFile exportCsv() {
        return tools.execute("alertify_procedure_export_csv", () -> support.csv("alertify-procedures.csv", csv.exportCsv()));
    }

    @AlertifyTool(name = "alertify_procedure_import_csv", description = "Import procedures from a bounded UTF-8 CSV text file", readOnly = false, refreshTargets = AiRefreshTarget.PROCEDURES)
    public ProcedureImportResult importCsv(@Valid AiTextFile file) {
        return tools.execute("alertify_procedure_import_csv", () -> csv.importCsv(support.csvUpload(file)));
    }

    @AlertifyTool(name = "alertify_procedure_execution_search", description = "Search procedure execution history; sensitive results remain redacted", readOnly = true)
    public AiPage<ProcedureExecutionResponse> executionSearch(Long procedureId, ProcedureExecutionStatus status, UUID executionId, @Valid AiPageRequest page) {
        return tools.execute("alertify_procedure_execution_search", () -> AiPage.from(
                executions.search(procedureId, status, executionId, page.pageable())));
    }

    @AlertifyTool(name = "alertify_procedure_tag_search", description = "Search tags scoped to procedures", readOnly = true)
    public AiPage<TagResponse> tagSearch(List<AiFilter> filters, @Valid AiPageRequest page) {
        return tools.execute("alertify_procedure_tag_search", () -> AiPage.from(tags.search(support.filters(filters), page.pageable())));
    }

    @AlertifyTool(name = "alertify_procedure_tag_create", description = "Create a tag scoped to procedures", readOnly = false, refreshTargets = AiRefreshTarget.PROCEDURES)
    public TagResponse tagCreate(@Valid TagCreateRequest request) {
        return tools.execute("alertify_procedure_tag_create", () -> tags.create(request));
    }

    @AlertifyTool(name = "alertify_procedure_tag_update", description = "Update a procedure tag using its current version", readOnly = false, refreshTargets = AiRefreshTarget.PROCEDURES)
    public TagResponse tagUpdate(long id, @Valid TagUpdateRequest request) {
        return tools.execute("alertify_procedure_tag_update", () -> tags.update(id, request));
    }

    @AlertifyTool(name = "alertify_procedure_tag_delete", description = "Delete a procedure tag using its current version", readOnly = false, destructiveHint = true, refreshTargets = AiRefreshTarget.PROCEDURES)
    public void tagDelete(long id, long version) {
        tools.execute("alertify_procedure_tag_delete", () -> tags.delete(id, version));
    }
}
