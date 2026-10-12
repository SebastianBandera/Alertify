package app.alertify.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import app.alertify.ai.api.AiPageRequest;
import app.alertify.ai.tools.AlertAiTools;
import app.alertify.ai.tools.ConfigurationAiTools;
import app.alertify.ai.tools.DashboardAiTools;
import app.alertify.ai.tools.HookAiTools;
import app.alertify.ai.tools.LocalizationAiTools;
import app.alertify.ai.tools.PipeAiTools;
import app.alertify.ai.tools.ProcedureAiTools;
import app.alertify.ai.tools.SystemAiTools;

class AiToolCatalogTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void exposesOnlyRequestedGroupsAllowedByTheCurrentRoles() {
        AiToolDescriptor alert = descriptor("alertify_alert_search", true);
        AiToolDescriptor dashboardRead = descriptor("alertify_dashboard_alert_chart", true);
        AiToolDescriptor dashboardRun = descriptor("alertify_dashboard_alert_run", false);
        AiToolCatalog catalog = new AiToolCatalog(Map.of(
                AiToolGroup.ALERTS, List.of(alert),
                AiToolGroup.DASHBOARD, List.of(dashboardRead, dashboardRun)));

        assertEquals(List.of(alert.callback()), catalog.toolsFor(authentication("ROLE_ADMIN"), Set.of(AiToolGroup.ALERTS)));
        assertEquals(List.of(dashboardRead.callback()), catalog.toolsFor(authentication("ROLE_DASHBOARD"), Set.of(AiToolGroup.DASHBOARD)));
        assertEquals(List.of(dashboardRead.callback(), dashboardRun.callback()), catalog.toolsFor(
                authentication("ROLE_DASHBOARD", "ROLE_DASHBOARD_RUN"), Set.of(AiToolGroup.DASHBOARD)));
        assertTrue(catalog.toolsFor(authentication("ROLE_DASHBOARD"), Set.of(AiToolGroup.ALERTS)).isEmpty());
        assertTrue(catalog.toolsFor(null, Set.of(AiToolGroup.ALERTS)).isEmpty());
    }

    @Test
    void buildsSpringAiSchemasForTheCompleteFacadeSurface() throws Exception {
        AiToolCatalog catalog = new AiToolCatalog(instantiate(AlertAiTools.class), instantiate(ProcedureAiTools.class),
                instantiate(PipeAiTools.class), instantiate(HookAiTools.class), instantiate(ConfigurationAiTools.class),
                instantiate(DashboardAiTools.class), instantiate(LocalizationAiTools.class), instantiate(SystemAiTools.class));

        List<ToolCallback> callbacks = catalog.toolsFor(authentication("ROLE_ADMIN", "ROLE_DASHBOARD", "ROLE_DASHBOARD_RUN"),
                Set.of(AiToolGroup.values()));

        assertTrue(callbacks.size() >= 80);
        assertEquals(callbacks.size(), callbacks.stream().map(callback -> callback.getToolDefinition().name()).distinct().count());
        assertTrue(callbacks.stream().allMatch(callback -> !callback.getToolDefinition().inputSchema().isBlank()));

        Map<String, AiToolDescriptor> descriptors = catalog.descriptorsFor(
                authentication("ROLE_ADMIN", "ROLE_DASHBOARD", "ROLE_DASHBOARD_RUN"), Set.of(AiToolGroup.values())).stream()
                .collect(java.util.stream.Collectors.toMap(AiToolDescriptor::name, descriptor -> descriptor));
        assertTrue(descriptors.get("alertify_alert_search").readOnly());
        assertTrue(descriptors.get("alertify_alert_search").description().contains("updatedAt DESC"));
        assertTrue(descriptors.get("alertify_alert_search").inputSchema().contains("Optional bounded pagination"));
        assertTrue(descriptors.get("alertify_alert_templates").description().contains("alertify_alert_template_source"));
        assertTrue(descriptors.get("alertify_alert_template_source").readOnly());
        assertTrue(descriptors.get("alertify_alert_template_source").idempotentHint());
        assertEquals(Set.of(), descriptors.get("alertify_alert_search").refreshTargets());
        assertEquals(Set.of(AiRefreshTarget.ALERTS, AiRefreshTarget.DASHBOARD), descriptors.get("alertify_alert_create").refreshTargets());
        assertTrue(descriptors.get("alertify_procedure_templates").description().contains("alertify_procedure_template_source"));
        assertTrue(descriptors.get("alertify_procedure_template_source").readOnly());
        assertTrue(descriptors.get("alertify_procedure_template_source").idempotentHint());
        assertTrue(descriptors.get("alertify_configuration_search").description().contains("~*Google*"));
        assertTrue(descriptors.get("alertify_configuration_search").description().contains("capped at 50"));
        JsonNode configurationSearchSchema = JSON.readTree(descriptors.get("alertify_configuration_search").inputSchema());
        assertEquals(1, schemaProperty(configurationSearchSchema, "size").path("minimum").intValue());
        assertEquals(AiPageRequest.MAX_SIZE, schemaProperty(configurationSearchSchema, "size").path("maximum").intValue());
        assertFalse(descriptors.get("alertify_alert_create").readOnly());
        assertTrue(descriptors.get("alertify_alert_update").description().contains("null or 0"));
        JsonNode alertUpdateSchema = JSON.readTree(descriptors.get("alertify_alert_update").inputSchema());
        JsonNode alertRequest = schemaProperty(alertUpdateSchema, "request");
        List<String> alertRequired = StreamSupport.stream(alertRequest.path("required").spliterator(), false).map(JsonNode::stringValue).toList();
        assertFalse(alertRequired.contains("persistentIssues"));
        assertFalse(alertRequired.contains("smartExecutionEnabled"));
        assertFalse(alertRequired.contains("smartExecutionIntervalHours"));
        assertFalse(alertRequired.contains("smartExecutionPolicy"));
        assertTrue(alertRequired.contains("enabled"));
        assertEquals("boolean", schemaProperty(alertRequest, "persistentIssues").path("type").stringValue());
        assertTrue(schemaProperty(alertRequest, "persistentIssues").path("description").stringValue().contains("null preserves"));
        assertEquals(List.of("TEXT", "CONFIGURATION", "SECRET", "PROCEDURE"),
                StreamSupport.stream(schemaProperty(alertUpdateSchema, "source").path("enum").spliterator(), false)
                        .map(JsonNode::stringValue).toList());
        assertTrue(schemaProperty(alertUpdateSchema, "configurationId").path("description").stringValue().contains("null or 0"));
        JsonNode procedureUpdateSchema = JSON.readTree(descriptors.get("alertify_procedure_update").inputSchema());
        assertEquals(List.of("TEXT", "CONFIGURATION", "SECRET", "PROCEDURE", "PIPE"),
                StreamSupport.stream(schemaProperty(procedureUpdateSchema, "source").path("enum").spliterator(), false)
                        .map(JsonNode::stringValue).toList());
        assertTrue(schemaProperty(procedureUpdateSchema, "pipeId").path("description").stringValue().contains("null or 0"));
        for (String toolName : List.of("alertify_alert_create", "alertify_alert_update", "alertify_procedure_create", "alertify_procedure_update")) {
            JsonNode schema = JSON.readTree(descriptors.get(toolName).inputSchema());
            JsonNode parameters = schemaProperty(schema, "parameters");
            JsonNode items = parameters.path("items");
            List<String> required = StreamSupport.stream(items.path("required").spliterator(), false).map(JsonNode::stringValue).toList();
            assertEquals(Set.of("parameterKey", "source"), Set.copyOf(required), toolName);
            assertTrue(items.path("properties").path("parameterKey").path("description").stringValue().contains("only once"), toolName);
            assertTrue(items.path("properties").path("configurationId").path("description").stringValue().contains("0 is invalid"), toolName);
            assertTrue(descriptors.get(toolName).description().contains("parameterKey"), toolName);
            if (toolName.endsWith("update"))
                assertTrue(parameters.path("description").stringValue().contains("Complete replacement list, not a patch"), toolName);

        }
        JsonNode hookUpdateSchema = JSON.readTree(descriptors.get("alertify_hook_update").inputSchema());
        assertEquals("string", schemaProperty(hookUpdateSchema, "rateLimitWindow").path("type").stringValue());
        assertTrue(schemaProperty(hookUpdateSchema, "tokenSecretId").path("description").stringValue().contains("null or 0"));
        JsonNode pipeUpdateSchema = JSON.readTree(descriptors.get("alertify_pipe_update").inputSchema());
        JsonNode bindingItems = schemaProperty(pipeUpdateSchema, "bindings").path("items");
        List<String> bindingRequired = StreamSupport.stream(bindingItems.path("required").spliterator(), false).map(JsonNode::stringValue).toList();
        assertTrue(bindingRequired.contains("targetParameterKey"));
        assertTrue(bindingRequired.contains("sourceStepKey"));
        assertFalse(bindingRequired.contains("sourceOutputKey"));
        assertFalse(bindingRequired.contains("sourceResultPointer"));
        assertFalse(bindingRequired.contains("valueExpression"));
        assertEquals("string", schemaProperty(pipeUpdateSchema, "finallyTimeout").path("type").stringValue());
        assertEquals("string", schemaProperty(pipeUpdateSchema, "timeout").path("type").stringValue());
        assertTrue(schemaProperty(pipeUpdateSchema, "sourceOutputKey").path("description").stringValue().contains("never both"));
        JsonNode expressionSchema = JSON.readTree(descriptors.get("alertify_configuration_expression_evaluate").inputSchema());
        assertTrue(schemaProperty(expressionSchema, "configurationId").path("description").stringValue().contains("null or 0"));
        assertFalse(descriptors.get("alertify_procedure_run").readOnly());
        assertEquals(Set.of(AiRefreshTarget.PROCEDURES), descriptors.get("alertify_procedure_run").refreshTargets());
        assertEquals(Set.of(AiRefreshTarget.CONFIGURATIONS), descriptors.get("alertify_configuration_create").refreshTargets());
        assertEquals(Set.of(AiRefreshTarget.SYSTEM), descriptors.get("alertify_system_configuration_update").refreshTargets());
        assertTrue(descriptors.get(LocalizationAiTools.TOOL_NAME).readOnly());
        assertTrue(descriptors.get(LocalizationAiTools.TOOL_NAME).idempotentHint());
        JsonNode configurationCreateSchema = JSON.readTree(descriptors.get("alertify_configuration_create").inputSchema());
        assertEquals("string", schemaProperty(configurationCreateSchema, "stringValue").path("type").stringValue());
        assertEquals("string", schemaProperty(configurationCreateSchema, "expressionValue").path("type").stringValue());
        assertEquals("integer", schemaProperty(configurationCreateSchema, "integerValue").path("type").stringValue());
        assertEquals("number", schemaProperty(configurationCreateSchema, "decimalValue").path("type").stringValue());
        assertEquals("boolean", schemaProperty(configurationCreateSchema, "booleanValue").path("type").stringValue());
        assertEquals("string", schemaProperty(configurationCreateSchema, "dateValue").path("type").stringValue());
        assertEquals("string", schemaProperty(configurationCreateSchema, "timeValue").path("type").stringValue());
        assertEquals("string", schemaProperty(configurationCreateSchema, "dateTimeValue").path("type").stringValue());
        assertEquals("string", schemaProperty(configurationCreateSchema, "jsonValue").path("type").stringValue());
        JsonNode systemUpdateSchema = JSON.readTree(descriptors.get("alertify_system_configuration_update").inputSchema());
        assertEquals("string", schemaProperty(systemUpdateSchema, "valueJson").path("type").stringValue());

        List<String> schemasWithUnboundedObjects = descriptors.values().stream()
                .filter(descriptor -> hasUnboundedObject(JSON.readTree(descriptor.inputSchema())))
                .map(AiToolDescriptor::name).sorted().toList();
        assertEquals(List.of(), schemasWithUnboundedObjects,
                "strict tools cannot contain object schemas without declared properties");
        String schemaEvidence = System.getenv("ALERTIFY_AI_SCHEMA_EVIDENCE_DIR");
        if (schemaEvidence != null && !schemaEvidence.isBlank()) {
            Path directory = Files.createDirectories(Path.of(schemaEvidence));
            Files.writeString(directory.resolve("alert-update-schema.json"), descriptors.get("alertify_alert_update").inputSchema());
            Files.writeString(directory.resolve("alert-create-schema.json"), descriptors.get("alertify_alert_create").inputSchema());
            Files.writeString(directory.resolve("procedure-update-schema.json"), descriptors.get("alertify_procedure_update").inputSchema());
            Files.writeString(directory.resolve("procedure-create-schema.json"), descriptors.get("alertify_procedure_create").inputSchema());
            Files.writeString(directory.resolve("pipe-update-schema.json"), descriptors.get("alertify_pipe_update").inputSchema());
        }
    }

    @Test
    void alertifyToolForwardsEverySpringToolAttribute() throws Exception {
        var method = ToolAttributeFixture.class.getDeclaredMethod("configured");
        Tool tool = AnnotatedElementUtils.findMergedAnnotation(method, Tool.class);

        assertEquals("configured_tool", tool.name());
        assertEquals("Configured description", tool.description());
        assertTrue(tool.returnDirect());
        assertSame(TestResultConverter.class, tool.resultConverter());
    }

    private static AiToolDescriptor descriptor(String name, boolean readOnly) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(definition.description()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        return new AiToolDescriptor(callback, name, name, false, DefaultToolCallResultConverter.class, readOnly,
                false, false, false, Set.of());
    }

    private static UsernamePasswordAuthenticationToken authentication(String... authorities) {
        return UsernamePasswordAuthenticationToken.authenticated("user", "unused",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    private static <T> T instantiate(Class<T> type) throws Exception {
        var constructor = type.getConstructors()[0];
        Object[] arguments = Stream.of(constructor.getParameterTypes()).map(org.mockito.Mockito::mock).toArray();
        return type.cast(constructor.newInstance(arguments));
    }

    private static boolean hasUnboundedObject(JsonNode schema) {
        if (schema.isObject() && schema.path("type").isString() && "object".equals(schema.path("type").stringValue()) && !schema.has("properties")
                && !schema.path("additionalProperties").isBoolean())
            return true;

        for (JsonNode child : schema)
            if (hasUnboundedObject(child))
                return true;

        return false;
    }

    private static JsonNode schemaProperty(JsonNode schema, String name) {
        JsonNode properties = schema.path("properties");
        if (properties.isObject() && properties.has(name))
            return properties.get(name);

        for (JsonNode child : schema) {
            JsonNode found = schemaProperty(child, name);
            if (!found.isMissingNode())
                return found;
        }
        return MissingNode.getInstance();
    }

    private static class ToolAttributeFixture {

        @AlertifyTool(name = "configured_tool", description = "Configured description", returnDirect = true,
                resultConverter = TestResultConverter.class, readOnly = true)
        String configured() { return "result"; }
    }

    private static class TestResultConverter implements ToolCallResultConverter {

        @Override
        public String convert(Object result, java.lang.reflect.Type returnType) {
            return String.valueOf(result);
        }
    }
}
