package app.alertify.ai.api;

/** Shared DSL reference exposed through configuration tool descriptions and schemas. */
public final class AiConfigurationExpressionDocumentation {

    public static final String LANGUAGE = "Alertify EXPRESSION DSL: {{configs.NAME}} references an existing non-BINARY configuration, including another EXPRESSION. Use the complete name, including internal spaces. Text outside {{...}} is literal; concatenate by adjacency, without +, concat or extra quote delimiters. Example: {{configs.kk base URL develop}}protocol/openid-connect/token. Slashes are not added or removed. {{env.NAME}} references an allowed environment variable; {{utils.DATE}} is a utility and {{utils.BASE64(text)}} is a utility function whose argument may contain references, for example {{utils.BASE64({{configs.NAME}})}}. Use suggestions for available environment variables, utilities and utility functions; do not invent names. Secret references are forbidden in configuration expressions. ";

    public static final String WORKFLOW = "Suggestions list currently available references, not language capabilities: configurations: [] means no configurations exist yet, not that composition is unsupported. If the user requests a base configuration and a dependent EXPRESSION, create the requested base first, then evaluate the dependent draft with alertify_configuration_expression_evaluate before saving it, following the existing approval policy. Set configurationName to the intended target name to check self-references. Correct evaluation errors rather than substituting a STRING for a requested EXPRESSION. ";

    public static final String WRITE_GUIDANCE = "For EXPRESSION syntax and available references see alertify_configuration_expression_suggestions. Create missing dependencies requested by the user first, then evaluate the draft with alertify_configuration_expression_evaluate before saving; do not replace a requested EXPRESSION with STRING. ";

    private AiConfigurationExpressionDocumentation() {
    }
}
