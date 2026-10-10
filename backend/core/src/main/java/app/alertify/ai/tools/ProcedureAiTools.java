package app.alertify.ai.tools;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import app.alertify.ai.AlertifyTool;
import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AiToolSupport;
import app.alertify.ai.api.AiFilter;
import app.alertify.ai.api.AiPage;
import app.alertify.ai.api.AiPageRequest;
import app.alertify.ai.api.AiTextFile;
import app.alertify.config.AuthorizationPolicies;
import app.alertify.configuration.api.TagCreateRequest;
import app.alertify.configuration.api.TagResponse;
import app.alertify.configuration.api.TagUpdateRequest;
import app.alertify.procedures.api.ProcedureBindingOptionsResponse;
import app.alertify.procedures.api.ProcedureCreateRequest;
import app.alertify.procedures.api.ProcedureDeletionImpactResponse;
import app.alertify.procedures.api.ProcedureExecutionResponse;
import app.alertify.procedures.api.ProcedureImportResult;
import app.alertify.procedures.api.ProcedureResponse;
import app.alertify.procedures.api.ProcedureTemplateResponse;
import app.alertify.procedures.api.ProcedureUpdateRequest;
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
    private final AiToolExecutor tools;
    private final AiToolSupport support;

    public ProcedureAiTools(ProcedureManagementService procedures, ProcedureCatalogService catalog,
            ProcedureCsvService csv, ProcedureExecutionQueryService executions, ProcedureTagService tags,
            AiToolExecutor tools, AiToolSupport support) {
        this.procedures = procedures;
        this.catalog = catalog;
        this.csv = csv;
        this.executions = executions;
        this.tags = tags;
        this.tools = tools;
        this.support = support;
    }

    @AlertifyTool(name = "alertify_procedure_search", description = "Search procedure definitions with bounded pagination", readOnly = true)
    public AiPage<ProcedureResponse> search(String name, Long templateId, List<Long> tagIds, boolean requireAllTags, @Valid AiPageRequest page) {
        return tools.execute("alertify_procedure_search", () -> AiPage.from(procedures.search(name, templateId,
                tagIds == null ? java.util.Set.of() : new LinkedHashSet<>(tagIds), requireAllTags, page.pageable())));
    }

    @AlertifyTool(name = "alertify_procedure_templates", description = "List procedure templates, parameters, outputs and sensitive-result flags", readOnly = true)
    public List<ProcedureTemplateResponse> templates() {
        return tools.execute("alertify_procedure_templates", catalog::templates);
    }

    @AlertifyTool(name = "alertify_procedure_binding_options", description = "List resources that procedure parameters may reference", readOnly = true)
    public ProcedureBindingOptionsResponse bindingOptions() {
        return tools.execute("alertify_procedure_binding_options", catalog::bindingOptions);
    }

    @AlertifyTool(name = "alertify_procedure_deletion_impact", description = "Preview references that affect deleting a procedure", readOnly = true)
    public ProcedureDeletionImpactResponse deletionImpact(long id) {
        return tools.execute("alertify_procedure_deletion_impact", () -> procedures.deletionImpact(id));
    }

    @AlertifyTool(name = "alertify_procedure_create", description = "Create a procedure using administration validation", readOnly = false)
    public ProcedureResponse create(@Valid ProcedureCreateRequest request) {
        return tools.execute("alertify_procedure_create", () -> procedures.create(request));
    }

    @AlertifyTool(name = "alertify_procedure_update", description = "Update a procedure using its current optimistic version", readOnly = false)
    public ProcedureResponse update(long id, @Valid ProcedureUpdateRequest request) {
        return tools.execute("alertify_procedure_update", () -> procedures.update(id, request));
    }

    @AlertifyTool(name = "alertify_procedure_run", description = "Trigger an immediate procedure execution", readOnly = false)
    public void run(long id) { tools.execute("alertify_procedure_run", () -> procedures.runNow(id)); }

    @AlertifyTool(name = "alertify_procedure_delete", description = "Delete a procedure using its current optimistic version", readOnly = false)
    public void delete(long id, long version) { tools.execute("alertify_procedure_delete", () -> procedures.delete(id, version)); }

    @AlertifyTool(name = "alertify_procedure_export_csv", description = "Export procedures as a bounded UTF-8 CSV text file", readOnly = true)
    public AiTextFile exportCsv() {
        return tools.execute("alertify_procedure_export_csv", () -> support.csv("alertify-procedures.csv", csv.exportCsv()));
    }

    @AlertifyTool(name = "alertify_procedure_import_csv", description = "Import procedures from a bounded UTF-8 CSV text file", readOnly = false)
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

    @AlertifyTool(name = "alertify_procedure_tag_create", description = "Create a tag scoped to procedures", readOnly = false)
    public TagResponse tagCreate(@Valid TagCreateRequest request) {
        return tools.execute("alertify_procedure_tag_create", () -> tags.create(request));
    }

    @AlertifyTool(name = "alertify_procedure_tag_update", description = "Update a procedure tag using its current version", readOnly = false)
    public TagResponse tagUpdate(long id, @Valid TagUpdateRequest request) {
        return tools.execute("alertify_procedure_tag_update", () -> tags.update(id, request));
    }

    @AlertifyTool(name = "alertify_procedure_tag_delete", description = "Delete a procedure tag using its current version", readOnly = false)
    public void tagDelete(long id, long version) {
        tools.execute("alertify_procedure_tag_delete", () -> tags.delete(id, version));
    }
}
