package app.alertify.ai.template;

import java.util.List;

import app.alertify.ai.api.AiPage;
import app.alertify.alerts.template.annotation.AlertParameterSource;

public record AiTemplateBindingCandidates(String templateType, String templateKey, String parameterKey, boolean bindingAllowed, List<AlertParameterSource> allowedSources, AiPage<Candidate> candidates) {
    public record Candidate(AlertParameterSource source, Long id, String name, String description, String valueType, Boolean writable, Boolean enabled) { }
}
