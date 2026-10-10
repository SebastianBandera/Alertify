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
import java.util.stream.Stream;

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

import app.alertify.ai.tools.AlertAiTools;
import app.alertify.ai.tools.ConfigurationAiTools;
import app.alertify.ai.tools.DashboardAiTools;
import app.alertify.ai.tools.HookAiTools;
import app.alertify.ai.tools.PipeAiTools;
import app.alertify.ai.tools.ProcedureAiTools;
import app.alertify.ai.tools.SystemAiTools;

class AiToolCatalogTest {

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
                instantiate(DashboardAiTools.class), instantiate(SystemAiTools.class));

        List<ToolCallback> callbacks = catalog.toolsFor(authentication("ROLE_ADMIN", "ROLE_DASHBOARD", "ROLE_DASHBOARD_RUN"),
                Set.of(AiToolGroup.values()));

        assertTrue(callbacks.size() >= 80);
        assertEquals(callbacks.size(), callbacks.stream().map(callback -> callback.getToolDefinition().name()).distinct().count());
        assertTrue(callbacks.stream().allMatch(callback -> !callback.getToolDefinition().inputSchema().isBlank()));

        Map<String, AiToolDescriptor> descriptors = catalog.descriptorsFor(
                authentication("ROLE_ADMIN", "ROLE_DASHBOARD", "ROLE_DASHBOARD_RUN"), Set.of(AiToolGroup.values())).stream()
                .collect(java.util.stream.Collectors.toMap(AiToolDescriptor::name, descriptor -> descriptor));
        assertTrue(descriptors.get("alertify_alert_search").readOnly());
        assertEquals("Search Alertify alert definitions with bounded pagination",
                descriptors.get("alertify_alert_search").description());
        assertFalse(descriptors.get("alertify_alert_create").readOnly());
        assertFalse(descriptors.get("alertify_procedure_run").readOnly());
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
        return new AiToolDescriptor(callback, name, name, false, DefaultToolCallResultConverter.class, readOnly);
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
