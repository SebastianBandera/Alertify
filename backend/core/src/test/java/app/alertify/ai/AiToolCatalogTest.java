package app.alertify.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
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
        ToolCallback alert = callback("alertify_alert_search");
        ToolCallback dashboardRead = callback("alertify_dashboard_alert_chart");
        ToolCallback dashboardRun = callback("alertify_dashboard_alert_run");
        AiToolCatalog catalog = new AiToolCatalog(Map.of(
                AiToolGroup.ALERTS, List.of(alert),
                AiToolGroup.DASHBOARD, List.of(dashboardRead, dashboardRun)));

        assertEquals(List.of(alert), catalog.toolsFor(authentication("ROLE_ADMIN"), Set.of(AiToolGroup.ALERTS)));
        assertEquals(List.of(dashboardRead), catalog.toolsFor(authentication("ROLE_DASHBOARD"), Set.of(AiToolGroup.DASHBOARD)));
        assertEquals(List.of(dashboardRead, dashboardRun), catalog.toolsFor(
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
    }

    private static ToolCallback callback(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
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
}
