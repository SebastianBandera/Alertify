package app.alertify.ai.template;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;

final class AiTemplateLookup {
    private AiTemplateLookup() { }

    static Selection select(String templateKey, AlertTemplateDefinitionRepository alerts, ProcedureTemplateDefinitionRepository procedures) {
        if (templateKey == null || templateKey.isBlank())
            throw new IllegalArgumentException("Exact templateKey is required; copy it from a template catalog or resource definition");

        String key = templateKey.strip();
        var alert = alerts.findByTemplateKey(key);
        var procedure = procedures.findByTemplateKey(key);
        if (alert.isPresent() && procedure.isPresent())
            throw new IllegalStateException("Template key is registered as both an alert and a procedure");

        if (alert.isPresent())
            return new Selection("ALERT", key, alert.get().getId());

        if (procedure.isPresent())
            return new Selection("PROCEDURE", key, procedure.get().getId());

        throw new ResourceNotFoundException("Template not found; use alertify_alert_templates or alertify_procedure_templates and copy the exact templateKey");
    }

    record Selection(String type, String key, Long id) { }
}
