package app.alertify.ai.tools;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import app.alertify.ai.AiConversationContext;
import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AlertifyTool;
import app.alertify.ai.localization.AiLocalizationSourceService;
import app.alertify.ai.localization.AiLocalizationSources;
import app.alertify.config.AuthorizationPolicies;

@Component
@PreAuthorize(AuthorizationPolicies.ADMIN)
public class LocalizationAiTools {

    public static final String TOOL_NAME = "alertify_localization_sources";

    private final AiLocalizationSourceService sources;
    private final AiToolExecutor tools;

    public LocalizationAiTools(AiLocalizationSourceService sources, AiToolExecutor tools) {
        this.sources = sources;
        this.tools = tools;
    }

    @AlertifyTool(name = TOOL_NAME, description = "Return the exact frontend localization TypeScript sources for the user's selected language: the localization key types, base translations, and fork extension translations. Use these exact values when answering about localized Alertify labels, template names, or descriptions.", readOnly = true, idempotentHint = true)
    public AiLocalizationSources sources() {
        return tools.execute(TOOL_NAME, () -> sources.sources(AiConversationContext.currentLocale()));
    }
}
