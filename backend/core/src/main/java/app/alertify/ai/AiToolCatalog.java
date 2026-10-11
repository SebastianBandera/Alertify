package app.alertify.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

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

    // Identifies the dashboard tool with the stricter DASHBOARD + DASHBOARD_RUN policy; keep it synchronized with DashboardAiTools.run's @AlertifyTool name.
    private static final String DASHBOARD_RUN_TOOL = "alertify_dashboard_alert_run";

    private final Map<AiToolGroup, List<AiToolDescriptor>> descriptors;

    @Autowired
    public AiToolCatalog(AlertAiTools alerts, ProcedureAiTools procedures, PipeAiTools pipes, HookAiTools hooks, ConfigurationAiTools configuration, DashboardAiTools dashboard, SystemAiTools system) {
        EnumMap<AiToolGroup, List<AiToolDescriptor>> values = new EnumMap<>(AiToolGroup.class);
        values.put(AiToolGroup.ALERTS, descriptors(alerts));
        values.put(AiToolGroup.PROCEDURES, descriptors(procedures));
        values.put(AiToolGroup.PIPES, descriptors(pipes));
        values.put(AiToolGroup.HOOKS, descriptors(hooks));
        values.put(AiToolGroup.CONFIGURATION, descriptors(configuration));
        values.put(AiToolGroup.DASHBOARD, descriptors(dashboard));
        values.put(AiToolGroup.SYSTEM, descriptors(system));
        descriptors = immutableDescriptors(values);
    }

    AiToolCatalog(Map<AiToolGroup, List<AiToolDescriptor>> descriptors) {
        this.descriptors = immutableDescriptors(descriptors);
    }

    public List<ToolCallback> toolsFor(Authentication authentication, Set<AiToolGroup> requestedGroups) {
        return descriptorsFor(authentication, requestedGroups).stream().map(AiToolDescriptor::callback).toList();
    }

    public List<AiToolDescriptor> descriptorsFor(Authentication authentication, Set<AiToolGroup> requestedGroups) {
        if (authentication == null || !authentication.isAuthenticated() || requestedGroups == null || requestedGroups.isEmpty())
            return List.of();

        boolean admin = has(authentication, "ROLE_ADMIN");
        boolean dashboard = has(authentication, "ROLE_DASHBOARD");
        boolean dashboardRun = dashboard && has(authentication, "ROLE_DASHBOARD_RUN");
        List<AiToolDescriptor> result = new ArrayList<>();
        for (AiToolGroup group : requestedGroups) {
            if (group == AiToolGroup.DASHBOARD) {
                if (admin || dashboard) {
                    descriptors.get(group).stream()
                            .filter(descriptor -> !DASHBOARD_RUN_TOOL.equals(descriptor.name()) || dashboardRun)
                            .forEach(result::add);
                }
            } else if (admin) {
                result.addAll(descriptors.get(group));
            }
        }
        return List.copyOf(result);
    }

    private static boolean has(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(value -> authority.equals(value.getAuthority()));
    }

    private static List<AiToolDescriptor> descriptors(Object toolObject) {
        Map<String, AlertifyTool> metadataByName = new LinkedHashMap<>();
        MethodIntrospector.selectMethods(AopUtils.getTargetClass(toolObject),
                (MethodIntrospector.MetadataLookup<AlertifyTool>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, AlertifyTool.class))
                .forEach((method, metadata) -> {
                    String name = ToolUtils.getToolName(method);
                    Assert.state(metadataByName.put(name, metadata) == null, () -> "Duplicate Alertify tool name: " + name);
                });

        return Arrays.stream(ToolCallbacks.from(toolObject)).map(callback -> {
            String name = callback.getToolDefinition().name();
            AlertifyTool metadata = metadataByName.get(name);
            Assert.state(metadata != null, () -> "Missing @AlertifyTool metadata for tool: " + name);
            return new AiToolDescriptor(callback, name, callback.getToolDefinition().description(),
                    callback.getToolMetadata().returnDirect(), metadata.resultConverter(), metadata.readOnly());
        }).toList();
    }

    private static Map<AiToolGroup, List<AiToolDescriptor>> immutableDescriptors(Map<AiToolGroup, List<AiToolDescriptor>> descriptors) {
        EnumMap<AiToolGroup, List<AiToolDescriptor>> copy = new EnumMap<>(AiToolGroup.class);
        descriptors.forEach((group, values) -> copy.put(group, List.copyOf(values)));
        return Map.copyOf(copy);
    }
}
