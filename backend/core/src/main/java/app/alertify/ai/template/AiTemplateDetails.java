package app.alertify.ai.template;

import java.util.List;

import app.alertify.alerts.api.AlertTemplateResponse;
import app.alertify.procedures.api.ProcedureTemplateResponse;

/** On-demand reference for one template; exactly one definition is populated. */
public record AiTemplateDetails(
    String templateType,
    String templateKey,
    AlertTemplateResponse alertTemplate,
    ProcedureTemplateResponse procedureTemplate,
    AiTemplateSource source,
    List<String> usageRules,
    List<AiTemplateExample> examples
) {
    public AiTemplateDetails {
        usageRules = List.copyOf(usageRules);
        examples = List.copyOf(examples);
    }
}
