package app.alertify.ai.tools;

import java.util.List;

import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AlertifyTool;
import app.alertify.ai.template.AiTemplateDetails;
import app.alertify.ai.template.AiTemplateDetailsService;
import app.alertify.ai.api.AiProcedureParameterValue;
import app.alertify.ai.template.AiTemplateBindingCandidates;
import app.alertify.ai.template.AiTemplateParameterValidation;
import app.alertify.ai.template.AiTemplateParametersService;
import app.alertify.config.AuthorizationPolicies;

@Component
@PreAuthorize(AuthorizationPolicies.ADMIN)
public class TemplateAiTools {
    public static final String TOOL_NAME = "alertify_template_details";
    public static final String VALIDATE_TOOL = "alertify_template_validate_parameters";
    public static final String CANDIDATES_TOOL = "alertify_template_binding_candidates";

    private final AiTemplateDetailsService templates;
    private final AiToolExecutor tools;
    private final AiTemplateParametersService parameters;

    public TemplateAiTools(AiTemplateDetailsService templates, AiToolExecutor tools, AiTemplateParametersService parameters) {
        this.templates = templates;
        this.tools = tools;
        this.parameters = parameters;
    }

    @AlertifyTool(name = TOOL_NAME, description = "Load the complete reference for one alert or procedure template: definition, parameters and binding restrictions, procedure outputs and sensitive-result flag, worker capability, exact Java source, usage rules and curated configuration examples showing variants. Provide its exact fully qualified templateKey from a template catalog or alert/procedure get/search result; type is detected automatically. Examples with bindingRequirement need existing references resolved through alertify_template_binding_candidates, never invented IDs. Use alertify_template_validate_parameters on resolved values before create/update. Custom templates without curated examples return an empty examples list. Localization keys can be resolved with alertify_localization_sources. This does not execute templates or read protected values.", readOnly = true, idempotentHint = true)
    public AiTemplateDetails details(@ToolParam(description = "Exact fully qualified templateKey copied from a template catalog or an alert/procedure get/search result. Do not invent a key, translate it, or provide a numeric resource ID or file path.") String templateKey) {
        return tools.execute(TOOL_NAME, () -> templates.details(templateKey));
    }

    @AlertifyTool(name = VALIDATE_TOOL, description = "Validate a complete proposed parameter list for one alert or procedure template without saving, executing, constructing templates or reading protected values. Detects unknown/duplicate/missing keys, defaults, literal Java types and options, source restrictions, reference existence, structured/binary value-type compatibility and writable-binding requirements. Supply the exact templateKey and parameters in create/update format. PIPE is allowed only for compatible procedure parameters; PIPE_OUTPUT is execution context, not a persisted source. Returns all detected issues without echoing submitted values. A valid result covers parameter rules and reference metadata only, not bound values, cross-parameter logic, connection health or live execution. Create/update will validate again.", readOnly = true, idempotentHint = true)
    public AiTemplateParameterValidation validate(@ToolParam(description = "Exact templateKey copied from a template catalog or alert/procedure get/search result") String templateKey, @ToolParam(description = "Complete proposed list, at most 100 entries. Empty applies defaults and reports missing required parameters. Use exact parameterKey and the requested source with a text value or existing positive reference ID. Do not submit protected values as text.") List<AiProcedureParameterValue> parameters) {
        return tools.execute(VALIDATE_TOOL, () -> this.parameters.validate(templateKey, parameters));
    }

    @AlertifyTool(name = CANDIDATES_TOOL, description = "List metadata of existing bindings compatible with exactly one template parameter, filtered by allowed source, bindingAllowed, Java/structured/binary type, declared value-type restrictions and required writability. Unlike global binding_options, these candidates are parameter-specific. No values are read or resources run. Pages are applied after compatibility filtering, sorted by name, source and ID. Disabled procedures/pipes remain visible with enabled=false because definition validity does not imply runtime availability. An empty page is not proof that another page has no candidates; inspect totalElements/totalPages. Defaults: page 0, size 20, maximum 100.", readOnly = true, idempotentHint = true)
    public AiTemplateBindingCandidates candidates(@ToolParam(description = "Exact templateKey copied from a template catalog or resource definition") String templateKey, @ToolParam(description = "Exact key of one parameter in the selected template") String parameterKey, @ToolParam(description = "Optional zero-based page, default 0", required = false) Integer page, @ToolParam(description = "Optional page size from 1 to 100, default 20", required = false) Integer size) {
        return tools.execute(CANDIDATES_TOOL, () -> parameters.candidates(templateKey, parameterKey, page, size));
    }
}
