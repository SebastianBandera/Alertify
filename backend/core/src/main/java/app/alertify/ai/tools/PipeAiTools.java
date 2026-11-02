package app.alertify.ai.tools;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import app.alertify.ai.AiRefreshTarget;
import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AiToolSupport;
import app.alertify.ai.patch.AiPatchService;
import app.alertify.ai.AlertifyTool;
import app.alertify.ai.api.AiFilter;
import app.alertify.ai.api.AiPage;
import app.alertify.ai.api.AiPageRequest;
import app.alertify.ai.api.AiPipeCreateRequest;
import app.alertify.ai.api.AiPipeUpdateRequest;
import app.alertify.ai.api.AiTextFile;
import app.alertify.config.AuthorizationPolicies;
import app.alertify.configuration.api.TagCreateRequest;
import app.alertify.configuration.api.TagResponse;
import app.alertify.configuration.api.TagUpdateRequest;
import app.alertify.pipes.api.PipeDeletionImpactResponse;
import app.alertify.pipes.api.PipeExecutionResponse;
import app.alertify.pipes.api.PipeImportResult;
import app.alertify.pipes.api.PipeOptionsResponse;
import app.alertify.pipes.api.PipeResponse;
import app.alertify.pipes.service.PipeCsvService;
import app.alertify.pipes.service.PipeExecutionQueryService;
import app.alertify.pipes.service.PipeManagementService;
import app.alertify.pipes.service.PipeTagService;
import jakarta.validation.Valid;

@Component
@Validated
@PreAuthorize(AuthorizationPolicies.ADMIN)
public class PipeAiTools {

    private final AiPatchService patches;
    private final PipeManagementService pipes;
    private final PipeCsvService csv;
    private final PipeExecutionQueryService executions;
    private final PipeTagService tags;
    private final AiToolExecutor tools;
    private final AiToolSupport support;

    public PipeAiTools(PipeManagementService pipes, PipeCsvService csv, PipeExecutionQueryService executions,
            PipeTagService tags, AiToolExecutor tools, AiToolSupport support, AiPatchService patches) {
        this.patches = patches;
        this.pipes = pipes;
        this.csv = csv;
        this.executions = executions;
        this.tags = tags;
        this.tools = tools;
        this.support = support;
    }

    @AlertifyTool(name = "alertify_pipe_patch", description = "Preferred tool for partial pipe edits. Read the current definition/version first; send only requested changes. Editable root fields: name, description, enabled, allowConcurrentExecutions, finallyTimeout (ISO duration), steps and tagIds. Step fields: key, type, phase, resourceId, timeout, continueOn, bindings; binding fields: targetParameterKey, sourceStepKey, sourceOutputKey, sourceResultPointer, valueExpression. tagIds is the sorted list of tag IDs from the read response. Atomic operations validate the complete result and verify persistence. The coordinator automatically rereads after commit. Before editing existing elements test parameterKey for parameters, key for steps, both type and resourceId for targets, targetParameterKey for bindings. Full update is only for explicitly requested full replacement.", readOnly = false, refreshTargets = AiRefreshTarget.PIPES)
    public PipeResponse patch(long id, @Valid app.alertify.ai.api.AiPatchRequest request) {
        return tools.execute("alertify_pipe_patch", () -> {
            return patches.patch(id, request, app.alertify.pipes.model.Pipe.class,
                    app.alertify.pipes.api.PipeUpdateRequest.class, () -> pipes.get(id), complete -> pipes.updatePartial(id, complete));
        });
    }

    @AlertifyTool(name = "alertify_pipe_tag_patch", description = "Preferred partial edit of a PIPE tag by tag ID and version. Editable fields: name and color. Omitted fields and associations remain unchanged. Server verifies persistence; coordinator automatically rereads.", readOnly = false, refreshTargets = AiRefreshTarget.PIPES)
    public TagResponse tagPatch(long id, @Valid app.alertify.ai.api.AiPatchRequest request) {
        return tools.execute("alertify_pipe_tag_patch", () -> patches.patch(id, request, app.alertify.jpa.entity.Tag.class,
                TagUpdateRequest.class, () -> tags.get(id), complete -> tags.update(id, complete)));
    }

    @AlertifyTool(name = "alertify_pipe_search", description = "Search pipe definitions with bounded pagination", readOnly = true)
    public AiPage<PipeResponse> search(String name, @Valid AiPageRequest page) {
        return tools.execute("alertify_pipe_search", () -> AiPage.from(pipes.search(name, page.pageable())));
    }

    @AlertifyTool(name = "alertify_pipe_get", description = "Get one pipe definition by its pipe ID. This reads a pipe, not a tag; tag IDs belong to a separate namespace. For a tag scoped to PIPE use alertify_pipe_tag_get with the tag ID.", readOnly = true)
    public PipeResponse get(long id) { return tools.execute("alertify_pipe_get", () -> pipes.get(id)); }

    @AlertifyTool(name = "alertify_pipe_options", description = "List resources and bindings available when defining a pipe", readOnly = true)
    public PipeOptionsResponse options() { return tools.execute("alertify_pipe_options", pipes::options); }

    @AlertifyTool(name = "alertify_pipe_deletion_impact", description = "Preview references that affect deleting a pipe", readOnly = true)
    public PipeDeletionImpactResponse deletionImpact(long id) {
        return tools.execute("alertify_pipe_deletion_impact", () -> pipes.deletionImpact(id));
    }

    @AlertifyTool(name = "alertify_pipe_create", description = "Create a pipe using administration validation", readOnly = false, refreshTargets = AiRefreshTarget.PIPES)
    public PipeResponse create(@Valid AiPipeCreateRequest request) {
        return tools.execute("alertify_pipe_create", () -> pipes.create(request.toRequest()));
    }

    @AlertifyTool(name = "alertify_pipe_update", description = "Full replacement: prefer alertify_pipe_patch for partial edits. Update a pipe using its current optimistic version. Read its complete definition first and preserve every unchanged step and binding. Each binding selects either an artifact sourceOutputKey or a JSON sourceResultPointer, never both; use null for the unselected source. Changing a valueExpression does not authorize changing another binding's source.", readOnly = false, refreshTargets = AiRefreshTarget.PIPES)
    public PipeResponse update(long id, @Valid AiPipeUpdateRequest request) {
        return tools.execute("alertify_pipe_update", () -> pipes.update(id, request.toRequest()));
    }

    @AlertifyTool(name = "alertify_pipe_run", description = "Trigger an immediate pipe execution and return its execution ID", readOnly = false, openWorldHint = true, refreshTargets = AiRefreshTarget.PIPES)
    public UUID run(long id) { return tools.execute("alertify_pipe_run", () -> pipes.run(id)); }

    @AlertifyTool(name = "alertify_pipe_delete", description = "Delete a pipe using its current optimistic version", readOnly = false, destructiveHint = true, refreshTargets = AiRefreshTarget.PIPES)
    public void delete(long id, long version) { tools.execute("alertify_pipe_delete", () -> pipes.delete(id, version)); }

    @AlertifyTool(name = "alertify_pipe_export_csv", description = "Export pipes as a bounded UTF-8 CSV text file", readOnly = true)
    public AiTextFile exportCsv() {
        return tools.execute("alertify_pipe_export_csv", () -> support.csv("alertify-pipes.csv", csv.exportCsv()));
    }

    @AlertifyTool(name = "alertify_pipe_import_csv", description = "Import pipes from a bounded UTF-8 CSV text file", readOnly = false, refreshTargets = AiRefreshTarget.PIPES)
    public PipeImportResult importCsv(@Valid AiTextFile file) {
        return tools.execute("alertify_pipe_import_csv", () -> csv.importCsv(support.csvUpload(file)));
    }

    @AlertifyTool(name = "alertify_pipe_execution_search", description = "Search pipe execution history", readOnly = true)
    public AiPage<PipeExecutionResponse> executionSearch(Long pipeId, @Valid AiPageRequest page) {
        return tools.execute("alertify_pipe_execution_search", () -> AiPage.from(executions.search(pipeId, page.pageable())));
    }

    @AlertifyTool(name = "alertify_pipe_execution_get", description = "Get one pipe execution by execution UUID", readOnly = true)
    public PipeExecutionResponse executionGet(UUID executionId) {
        return tools.execute("alertify_pipe_execution_get", () -> executions.get(executionId));
    }

    @AlertifyTool(name = "alertify_pipe_tag_get", description = "Get one tag scoped to PIPE by its tag ID, including its current version. This does not read a pipe. Use this before updating or deleting a PIPE tag and to verify the tag after a write.", readOnly = true)
    public TagResponse tagGet(long id) { return tools.execute("alertify_pipe_tag_get", () -> tags.get(id)); }

    @AlertifyTool(name = "alertify_pipe_tag_search", description = "Search tags scoped to PIPE, not pipe definitions. Filter field id identifies a tag ID; it is independent of pipe IDs.", readOnly = true)
    public AiPage<TagResponse> tagSearch(@ToolParam(description = "PIPE tag filter fields: id, version, name, color, createdAt (alias created), updatedAt (alias modified). The field id is the tag ID. There is no pipeId filter or association lookup in this tool. To read tag 94 directly use alertify_pipe_tag_get(id=94).") List<AiFilter> filters, @Valid AiPageRequest page) {
        return tools.execute("alertify_pipe_tag_search", () -> AiPage.from(tags.search(support.filters(filters), page.pageable())));
    }

    @AlertifyTool(name = "alertify_pipe_tag_create", description = "Create a tag scoped to pipes", readOnly = false, refreshTargets = AiRefreshTarget.PIPES)
    public TagResponse tagCreate(@Valid TagCreateRequest request) {
        return tools.execute("alertify_pipe_tag_create", () -> tags.create(request));
    }

    @AlertifyTool(name = "alertify_pipe_tag_update", description = "Full replacement: prefer alertify_pipe_tag_patch for partial edits. Update a pipe tag using its current version", readOnly = false, refreshTargets = AiRefreshTarget.PIPES)
    public TagResponse tagUpdate(long id, @Valid TagUpdateRequest request) {
        return tools.execute("alertify_pipe_tag_update", () -> tags.update(id, request));
    }

    @AlertifyTool(name = "alertify_pipe_tag_delete", description = "Delete a tag scoped to PIPE using its tag ID and current tag version from alertify_pipe_tag_get. This does not delete a pipe. The backend checks associations and rejects an assigned tag with PIPE_TAG_IN_USE without detaching it. After reading the tag and preparing its snapshot, submit this tool for an explicitly requested ASK deletion even when a read tool does not expose associations; do not require a nonexistent association filter. Do not detach the tag or delete associated pipes without a separate user request.", readOnly = false, destructiveHint = true, refreshTargets = AiRefreshTarget.PIPES)
    public void tagDelete(long id, long version) { tools.execute("alertify_pipe_tag_delete", () -> tags.delete(id, version)); }
}
