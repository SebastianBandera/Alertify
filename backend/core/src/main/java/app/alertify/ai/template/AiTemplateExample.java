package app.alertify.ai.template;

import java.util.List;

import app.alertify.alerts.template.annotation.AlertParameterSource;

/** A configuration recipe. Binding requirements are instructions, never fabricated IDs or credentials. */
public record AiTemplateExample(String name, String description, List<Parameter> parameters, List<String> notes, boolean requiresBindingResolution) {
    public record Parameter(String parameterKey, AlertParameterSource source, String textValue, String bindingRequirement) { }
}
