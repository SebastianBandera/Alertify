package app.alertify.ai.template;

import java.util.List;

/** No submitted values or protected resource content are echoed in validation results. */
public record AiTemplateParameterValidation(String templateType, String templateKey, boolean valid, List<Issue> issues, List<String> defaultsApplied, List<String> omittedOptionalParameters, String scope) {
    public record Issue(String parameterKey, String code, String message) { }
}
