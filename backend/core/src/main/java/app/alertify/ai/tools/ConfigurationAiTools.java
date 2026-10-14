package app.alertify.ai.tools;

import java.util.List;

import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import tools.jackson.databind.json.JsonMapper;

import app.alertify.ai.AiRefreshTarget;
import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AiToolSupport;
import app.alertify.ai.AlertifyTool;
import app.alertify.ai.api.AiConfigurationCreateRequest;
import app.alertify.ai.api.AiConfigurationExpressionEvaluationRequest;
import app.alertify.ai.api.AiConfigurationUpdateRequest;
import app.alertify.ai.api.AiFilter;
import app.alertify.ai.api.AiPage;
import app.alertify.ai.api.AiPageRequest;
import app.alertify.ai.api.AiSecretReference;
import app.alertify.ai.api.AiSystemConfigurationUpdateRequest;
import app.alertify.ai.api.AiTextFile;
import app.alertify.config.AuthorizationPolicies;
import app.alertify.configuration.api.ConfigurationExpressionEvaluationResponse;
import app.alertify.configuration.api.ConfigurationExpressionSuggestionsResponse;
import app.alertify.configuration.api.ConfigurationImportResult;
import app.alertify.configuration.api.ConfigurationResponse;
import app.alertify.configuration.api.ConfigurationUsagesResponse;
import app.alertify.configuration.api.TagCreateRequest;
import app.alertify.configuration.api.TagResponse;
import app.alertify.configuration.api.TagUpdateRequest;
import app.alertify.configuration.service.ApplicationConfigurationService;
import app.alertify.configuration.service.ConfigurationExpressionService;
import app.alertify.configuration.service.ConfigurationTagService;
import app.alertify.configuration.service.ConfigurationUsageService;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.secret.api.SecretResponse;
import app.alertify.services.secret.ApplicationSecretService;
import app.alertify.systemconfiguration.api.SystemConfigurationResponse;
import app.alertify.systemconfiguration.service.SystemConfigurationService;
import jakarta.validation.Valid;

@Component
@Validated
@PreAuthorize(AuthorizationPolicies.ADMIN)
public class ConfigurationAiTools {

    private final ApplicationConfigurationService configurations;
    private final ConfigurationExpressionService expressions;
    private final ConfigurationUsageService usages;
    private final ConfigurationTagService tags;
    private final ApplicationSecretService secrets;
    private final SystemConfigurationService systemConfigurations;
    private final AiToolExecutor tools;
    private final AiToolSupport support;
    private final JsonMapper json;

    public ConfigurationAiTools(ApplicationConfigurationService configurations,
            ConfigurationExpressionService expressions, ConfigurationUsageService usages,
            ConfigurationTagService tags, ApplicationSecretService secrets,
            SystemConfigurationService systemConfigurations, AiToolExecutor tools, AiToolSupport support, JsonMapper json) {
        this.configurations = configurations;
        this.expressions = expressions;
        this.usages = usages;
        this.tags = tags;
        this.secrets = secrets;
        this.systemConfigurations = systemConfigurations;
        this.tools = tools;
        this.support = support;
        this.json = json;
    }

    @AlertifyTool(name = "alertify_configuration_search", description = "Search application configurations; binary entries expose metadata only. String filters are exact by default. For a case-insensitive, accent-insensitive partial name match like the frontend search, use a name filter with value ~*Google*, where ~ enables flexible text matching and * is the wildcard. Page size is capped at 50; use subsequent page numbers when totalPages indicates more results. For resources without tags, inspect each returned tags array and include only empty arrays across every page; omitting a tag filter does not mean without tags.", readOnly = true)
    public AiPage<ConfigurationResponse> search(@ToolParam(description = "Configuration filter fields: id, version, name, description, valueType (alias type), createdAt (alias created), updatedAt (alias modified), valueContains, tagId and tagOperator. For a tag use [{field: tagId, value: 77}], never tagIds. Repeat tagId once per positive tag ID; do not combine IDs in a comma-separated value. tagOperator is OR (default) or AND. Filter fields are tool-specific; do not infer them from response properties.") List<AiFilter> filters, @Valid AiPageRequest page) {
        return tools.execute("alertify_configuration_search", () -> {
            var result = configurations.search(support.filters(filters), page.pageable());
            var counts = usages.getForIds(result.getContent().stream().map(ConfigurationResponse::id).toList());
            return AiPage.from(result.map(value -> value.withUsageCount(counts.get(value.id()).totalCount())));
        });
    }

    @AlertifyTool(name = "alertify_configuration_get", description = "Get one application configuration; binary content is never returned", readOnly = true)
    public ConfigurationResponse get(long id) {
        return tools.execute("alertify_configuration_get", () -> withUsageCount(configurations.get(id)));
    }

    @AlertifyTool(name = "alertify_configuration_usages", description = "List resources that reference an application configuration", readOnly = true)
    public ConfigurationUsagesResponse configurationUsages(long id) {
        return tools.execute("alertify_configuration_usages", () -> usages.get(id));
    }

    @AlertifyTool(name = "alertify_configuration_expression_suggestions", description = "List safe references available to configuration expressions", readOnly = true)
    public ConfigurationExpressionSuggestionsResponse expressionSuggestions() {
        return tools.execute("alertify_configuration_expression_suggestions", expressions::suggestions);
    }

    @AlertifyTool(name = "alertify_configuration_expression_evaluate", description = "Evaluate a draft configuration expression; secret references are forbidden", readOnly = true)
    public ConfigurationExpressionEvaluationResponse evaluateExpression(@Valid AiConfigurationExpressionEvaluationRequest request) {
        return tools.execute("alertify_configuration_expression_evaluate", () -> expressions.evaluate(request.toRequest()));
    }

    @AlertifyTool(name = "alertify_configuration_create", description = "Create a non-binary application configuration. valueType selects the only interpreted value field: STRING stringValue, EXPRESSION expressionValue, INTEGER integerValue, DECIMAL decimalValue, BOOLEAN booleanValue, DATE dateValue (yyyy-MM-dd), TIME timeValue (HH:mm), DATE_TIME dateTimeValue (ISO-8601 with offset), or JSON jsonValue (serialized JSON). The strict schema includes every value field; use neutral placeholders for fields not selected by valueType because Alertify ignores them.", readOnly = false, refreshTargets = AiRefreshTarget.CONFIGURATIONS)
    public ConfigurationResponse create(@Valid AiConfigurationCreateRequest request) {
        return tools.execute("alertify_configuration_create", () -> {
            requireNonBinary(request.valueType());
            return withUsageCount(configurations.create(request.toRequest(json)));
        });
    }

    @AlertifyTool(name = "alertify_configuration_update", description = "Update a non-binary application configuration using its current version. valueType selects the only interpreted value field: STRING stringValue, EXPRESSION expressionValue, INTEGER integerValue, DECIMAL decimalValue, BOOLEAN booleanValue, DATE dateValue (yyyy-MM-dd), TIME timeValue (HH:mm), DATE_TIME dateTimeValue (ISO-8601 with offset), or JSON jsonValue (serialized JSON). The strict schema includes every value field; use neutral placeholders for fields not selected by valueType because Alertify ignores them.", readOnly = false, refreshTargets = AiRefreshTarget.CONFIGURATIONS)
    public ConfigurationResponse update(long id, @Valid AiConfigurationUpdateRequest request) {
        return tools.execute("alertify_configuration_update", () -> {
            requireNonBinary(configurations.get(id).valueType());
            requireNonBinary(request.valueType());
            return withUsageCount(configurations.update(id, request.toRequest(json)));
        });
    }

    @AlertifyTool(name = "alertify_configuration_delete", description = "Delete a non-binary application configuration using its current version", readOnly = false, destructiveHint = true, refreshTargets = AiRefreshTarget.CONFIGURATIONS)
    public void delete(long id, long version) {
        tools.execute("alertify_configuration_delete", () -> {
            requireNonBinary(configurations.get(id).valueType());
            configurations.delete(id, version);
        });
    }

    @AlertifyTool(name = "alertify_configuration_export_csv", description = "Export configuration metadata and non-binary values as bounded UTF-8 CSV", readOnly = true)
    public AiTextFile exportCsv() {
        return tools.execute("alertify_configuration_export_csv",
                () -> support.csv("alertify-configurations.csv", configurations.exportCsv()));
    }

    @AlertifyTool(name = "alertify_configuration_import_csv", description = "Import non-binary configurations from bounded UTF-8 CSV", readOnly = false, refreshTargets = AiRefreshTarget.CONFIGURATIONS)
    public ConfigurationImportResult importCsv(@Valid AiTextFile file) {
        return tools.execute("alertify_configuration_import_csv",
                () -> configurations.importCsv(support.csvUpload(file)));
    }

    @AlertifyTool(name = "alertify_configuration_tag_search", description = "Search tags scoped to configurations", readOnly = true)
    public AiPage<TagResponse> tagSearch(List<AiFilter> filters, @Valid AiPageRequest page) {
        return tools.execute("alertify_configuration_tag_search",
                () -> AiPage.from(tags.search(support.filters(filters), page.pageable())));
    }

    @AlertifyTool(name = "alertify_configuration_tag_get", description = "Get one configuration tag", readOnly = true)
    public TagResponse tagGet(long id) { return tools.execute("alertify_configuration_tag_get", () -> tags.get(id)); }

    @AlertifyTool(name = "alertify_configuration_tag_create", description = "Create a tag scoped to configurations", readOnly = false, refreshTargets = AiRefreshTarget.CONFIGURATIONS)
    public TagResponse tagCreate(@Valid TagCreateRequest request) {
        return tools.execute("alertify_configuration_tag_create", () -> tags.create(request));
    }

    @AlertifyTool(name = "alertify_configuration_tag_update", description = "Update a configuration tag using its current version", readOnly = false, refreshTargets = AiRefreshTarget.CONFIGURATIONS)
    public TagResponse tagUpdate(long id, @Valid TagUpdateRequest request) {
        return tools.execute("alertify_configuration_tag_update", () -> tags.update(id, request));
    }

    @AlertifyTool(name = "alertify_configuration_tag_delete", description = "Delete a configuration tag using its current version", readOnly = false, destructiveHint = true, refreshTargets = AiRefreshTarget.CONFIGURATIONS)
    public void tagDelete(long id, long version) {
        tools.execute("alertify_configuration_tag_delete", () -> tags.delete(id, version));
    }

    @AlertifyTool(name = "alertify_secret_reference_search", description = "Search existing secret names and descriptions; values and operational metadata are never returned", readOnly = true)
    public AiPage<AiSecretReference> secretReferences(List<AiFilter> filters, @Valid AiPageRequest page) {
        return tools.execute("alertify_secret_reference_search", () -> AiPage.from(
                secrets.search(support.filters(filters), page.pageable()).map(ConfigurationAiTools::reference)));
    }

    @AlertifyTool(name = "alertify_secret_reference_get", description = "Get only the ID, name and description of an existing secret", readOnly = true)
    public AiSecretReference secretReference(long id) {
        return tools.execute("alertify_secret_reference_get", () -> reference(secrets.get(id)));
    }

    @AlertifyTool(name = "alertify_system_configuration_search", description = "List system configurations; hidden values remain null", readOnly = true)
    public AiPage<SystemConfigurationResponse> systemSearch(@Valid AiPageRequest page) {
        return tools.execute("alertify_system_configuration_search",
                () -> AiPage.from(systemConfigurations.search(page.pageable())));
    }

    @AlertifyTool(name = "alertify_system_configuration_get", description = "Get a system configuration; hidden values remain null", readOnly = true)
    public SystemConfigurationResponse systemGet(long id) {
        return tools.execute("alertify_system_configuration_get", () -> systemConfigurations.get(id));
    }

    @AlertifyTool(name = "alertify_system_configuration_update", description = "Update a visible functional system configuration using valueJson as a serialized JSON value; hidden keys and rotations are unavailable", readOnly = false, refreshTargets = AiRefreshTarget.SYSTEM)
    public SystemConfigurationResponse systemUpdate(long id, @Valid AiSystemConfigurationUpdateRequest request) {
        return tools.execute("alertify_system_configuration_update", () -> {
            SystemConfigurationResponse current = systemConfigurations.get(id);
            if (current.valueHidden())
                throw new IllegalArgumentException("AI tools cannot modify hidden system configurations");

            return systemConfigurations.update(id, request.toRequest(json));
        });
    }

    private ConfigurationResponse withUsageCount(ConfigurationResponse response) {
        return response.withUsageCount(usages.getForIds(List.of(response.id())).get(response.id()).totalCount());
    }

    private static AiSecretReference reference(SecretResponse value) {
        return new AiSecretReference(value.id(), value.name(), value.description());
    }

    private static void requireNonBinary(ConfigurationValueType valueType) {
        if (valueType == ConfigurationValueType.BINARY)
            throw new IllegalArgumentException("Binary configuration operations are not available to AI tools");
    }
}
