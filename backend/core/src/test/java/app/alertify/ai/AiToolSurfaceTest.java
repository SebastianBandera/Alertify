package app.alertify.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import app.alertify.ai.api.AiSecretReference;
import app.alertify.ai.tools.AlertAiTools;
import app.alertify.ai.tools.ConfigurationAiTools;
import app.alertify.ai.tools.DashboardAiTools;
import app.alertify.ai.tools.HookAiTools;
import app.alertify.ai.tools.LocalizationAiTools;
import app.alertify.ai.tools.PipeAiTools;
import app.alertify.ai.tools.ProcedureAiTools;
import app.alertify.ai.tools.SystemAiTools;
import app.alertify.ai.tools.TemplateAiTools;
import app.alertify.config.AuthorizationPolicies;

class AiToolSurfaceTest {

    private static final List<Class<?>> TOOL_CLASSES = List.of(AlertAiTools.class, ProcedureAiTools.class,
            PipeAiTools.class, HookAiTools.class, ConfigurationAiTools.class, DashboardAiTools.class,
            LocalizationAiTools.class, SystemAiTools.class, TemplateAiTools.class);

    @Test
    void toolNamesAreUniqueAndExcludeCredentialRotationAndBinaryContent() {
        List<String> names = TOOL_CLASSES.stream()
                .flatMap(type -> Arrays.stream(type.getMethods()))
                .map(method -> method.getAnnotation(AlertifyTool.class))
                .filter(java.util.Objects::nonNull)
                .map(AlertifyTool::name)
                .toList();

        assertTrue(names.size() >= 80);
        assertEquals(names.size(), names.stream().distinct().count());
        names.stream().map(value -> value.toLowerCase(Locale.ROOT)).forEach(name -> {
            assertFalse(name.contains("password"));
            assertFalse(name.contains("rotate"));
            assertFalse(name.contains("regenerate"));
            assertFalse(name.contains("binary_download"));
        });
    }

    @Test
    void secretReferencesContainOnlyMinimalIdentificationMetadata() {
        assertEquals(List.of("id", "name", "description"), Arrays.stream(AiSecretReference.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList());
    }

    @Test
    void everyToolHasAuthorizationAtItsFacadeBoundary() {
        TOOL_CLASSES.stream().filter(type -> type != DashboardAiTools.class).forEach(type ->
                assertEquals(AuthorizationPolicies.ADMIN, type.getAnnotation(PreAuthorize.class).value()));
        Arrays.stream(DashboardAiTools.class.getMethods())
                .filter(method -> method.isAnnotationPresent(AlertifyTool.class))
                .forEach(method -> assertTrue(method.isAnnotationPresent(PreAuthorize.class)));
        assertEquals(AuthorizationPolicies.DASHBOARD_RUN, Arrays.stream(DashboardAiTools.class.getMethods())
                .filter(method -> method.getAnnotation(AlertifyTool.class) != null)
                .filter(method -> method.getAnnotation(AlertifyTool.class).name().equals("alertify_dashboard_alert_run"))
                .findFirst()
                .orElseThrow()
                .getAnnotation(PreAuthorize.class)
                .value());
    }

    @Test
    void readToolsDoNotInvalidateResourcesAndEveryWriteToolDeclaresSpecificTargets() {
        TOOL_CLASSES.stream()
                .flatMap(type -> Arrays.stream(type.getMethods()))
                .map(method -> method.getAnnotation(AlertifyTool.class))
                .filter(java.util.Objects::nonNull)
                .forEach(tool -> {
                    if (tool.readOnly())
                        assertEquals(0, tool.refreshTargets().length, tool.name());
                    else
                        assertTrue(tool.refreshTargets().length > 0, tool.name());

                    assertFalse(Arrays.asList(tool.refreshTargets()).contains(AiRefreshTarget.ALL), tool.name());
                });
    }
}
