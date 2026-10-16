package app.alertify.alerts.template;

import java.util.List;

import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;

/** Value-free parameter rules shared by administration, AI validation and candidates. */
public record TemplateParameterRules(String key, String javaType, List<String> options, boolean bindingAllowed, boolean writableBindingRequired, boolean required, String defaultValue, List<AlertParameterSource> allowedSources, List<String> allowedConfigurationValueTypes, List<String> allowedSecretValueTypes, boolean procedureTemplate) {
    public static TemplateParameterRules of(app.alertify.alerts.api.AlertTemplateParameterResponse parameter) {
        return new TemplateParameterRules(parameter.key(), parameter.javaType(), parameter.options(), parameter.bindingAllowed(), parameter.writableBindingRequired(),
                parameter.required(), parameter.defaultValue(), parameter.allowedSources(), parameter.allowedConfigurationValueTypes(), parameter.allowedSecretValueTypes(), false);
    }

    public static TemplateParameterRules of(app.alertify.procedures.api.ProcedureTemplateParameterResponse parameter) {
        return new TemplateParameterRules(parameter.key(), parameter.javaType(), parameter.options(), parameter.bindingAllowed(), false,
                parameter.required(), parameter.defaultValue(), parameter.allowedSources(), parameter.allowedConfigurationValueTypes(), parameter.allowedSecretValueTypes(), true);
    }

    public static TemplateParameterRules of(AlertTemplateParameterDefinition parameter) {
        return new TemplateParameterRules(parameter.getParameterKey(), parameter.getJavaType(), parameter.getOptions(), parameter.isBindingAllowed(),
                parameter.isWritableBindingRequired(), parameter.isRequired(), parameter.getDefaultValue(), parameter.getAllowedSources(),
                parameter.getAllowedConfigurationValueTypes(), parameter.getAllowedSecretValueTypes(), false);
    }

    public static TemplateParameterRules of(ProcedureTemplateParameterDefinition parameter) {
        return new TemplateParameterRules(parameter.getParameterKey(), parameter.getJavaType(), parameter.getOptions(), parameter.isBindingAllowed(),
                false, parameter.isRequired(), parameter.getDefaultValue(), parameter.getAllowedSources(),
                parameter.getAllowedConfigurationValueTypes(), parameter.getAllowedSecretValueTypes(), true);
    }
}
