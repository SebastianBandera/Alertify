package app.alertify.ai;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import app.alertify.ai.tools.AlertAiTools;
import app.alertify.ai.tools.ConfigurationAiTools;
import app.alertify.ai.tools.DashboardAiTools;
import app.alertify.ai.tools.HookAiTools;
import app.alertify.ai.tools.PipeAiTools;
import app.alertify.ai.tools.ProcedureAiTools;
import app.alertify.ai.tools.SystemAiTools;

/** Selects a bounded set of callbacks by trusted domain and current roles. */
@Service
public class AiToolCatalog {

    // Identifies the dashboard tool with the stricter DASHBOARD + DASHBOARD_RUN policy; keep it synchronized with DashboardAiTools.run's @Tool name.
    private static final String DASHBOARD_RUN_TOOL = "alertify_dashboard_alert_run";

    private final Map<AiToolGroup, List<ToolCallback>> callbacks;

    public AiToolCatalog(AlertAiTools alerts, ProcedureAiTools procedures, PipeAiTools pipes, HookAiTools hooks, ConfigurationAiTools configuration, DashboardAiTools dashboard, SystemAiTools system) {
        EnumMap<AiToolGroup, List<ToolCallback>> values = new EnumMap<>(AiToolGroup.class);
        values.put(AiToolGroup.ALERTS, callbacks(alerts));
        values.put(AiToolGroup.PROCEDURES, callbacks(procedures));
        values.put(AiToolGroup.PIPES, callbacks(pipes));
        values.put(AiToolGroup.HOOKS, callbacks(hooks));
        values.put(AiToolGroup.CONFIGURATION, callbacks(configuration));
        values.put(AiToolGroup.DASHBOARD, callbacks(dashboard));
        values.put(AiToolGroup.SYSTEM, callbacks(system));
        callbacks = immutableCallbacks(values);
    }

    AiToolCatalog(Map<AiToolGroup, List<ToolCallback>> callbacks) {
        this.callbacks = immutableCallbacks(callbacks);
    }

    public List<ToolCallback> toolsFor(Authentication authentication, Set<AiToolGroup> requestedGroups) {
        if (authentication == null || !authentication.isAuthenticated() || requestedGroups == null || requestedGroups.isEmpty())
            return List.of();

        boolean admin = has(authentication, "ROLE_ADMIN");
        boolean dashboard = has(authentication, "ROLE_DASHBOARD");
        boolean dashboardRun = dashboard && has(authentication, "ROLE_DASHBOARD_RUN");
        List<ToolCallback> result = new ArrayList<>();
        for (AiToolGroup group : requestedGroups) {
            if (group == AiToolGroup.DASHBOARD) {
                if (admin || dashboard) {
                    callbacks.get(group).stream()
                            .filter(callback -> !DASHBOARD_RUN_TOOL.equals(callback.getToolDefinition().name()) || dashboardRun)
                            .forEach(result::add);
                }
            } else if (admin) {
                result.addAll(callbacks.get(group));
            }
        }
        return List.copyOf(result);
    }

    private static boolean has(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(value -> authority.equals(value.getAuthority()));
    }

    private static List<ToolCallback> callbacks(Object toolObject) {
        return List.of(ToolCallbacks.from(toolObject));
    }

    private static Map<AiToolGroup, List<ToolCallback>> immutableCallbacks(Map<AiToolGroup, List<ToolCallback>> callbacks) {
        EnumMap<AiToolGroup, List<ToolCallback>> copy = new EnumMap<>(AiToolGroup.class);
        callbacks.forEach((group, values) -> copy.put(group, List.copyOf(values)));
        return Map.copyOf(copy);
    }
}
