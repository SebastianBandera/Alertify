package app.alertify.alerts.template;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.scheduling.support.CronExpression;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.worker.contract.DatabaseCredentials;

/** Pure checks: no persistence, credential resolution, resource execution or template construction. */
public final class TemplateParameterValidation {
    private TemplateParameterValidation() { }

    public static void source(TemplateParameterRules rules, AlertParameterSource source) {
        if (source == null || !rules.allowedSources().contains(source))
            throw violation("SOURCE_NOT_ALLOWED", "Source " + source + " is not allowed for parameter '" + rules.key() + "'");

        if (source == AlertParameterSource.PIPE_OUTPUT || (!rules.procedureTemplate() && source == AlertParameterSource.PIPE))
            throw violation("EXECUTION_CONTEXT_ONLY", "Pipe output sources cannot be persisted as template parameters");

        if (source != AlertParameterSource.TEXT && !rules.bindingAllowed())
            throw violation("BINDING_NOT_ALLOWED", "binding is not allowed for parameter " + rules.key());

        boolean procedure = app.alertify.procedures.Procedure.class.getName().equals(rules.javaType());
        if ((procedure && source != AlertParameterSource.PROCEDURE) || (!rules.procedureTemplate() && !procedure && source == AlertParameterSource.PROCEDURE))
            throw violation("SOURCE_TYPE_MISMATCH", "Procedure bindings require a Procedure parameter");

        if (rules.writableBindingRequired() && source != AlertParameterSource.CONFIGURATION && source != AlertParameterSource.SECRET)
            throw violation("WRITABLE_BINDING_REQUIRED", "Parameter '" + rules.key() + "' requires a writable configuration or secret");
    }

    public static String text(TemplateParameterRules rules, String value) {
        if (app.alertify.procedures.Procedure.class.getName().equals(rules.javaType()))
            throw violation("SOURCE_TYPE_MISMATCH", "Procedure parameter '" + rules.key() + "' must use a procedure binding");

        if (value == null)
            throw violation("TEXT_REQUIRED", "Text value is required for parameter '" + rules.key() + "'");

        if (!rules.bindingAllowed() && !rules.options().contains(value))
            throw violation("OPTION_NOT_ALLOWED", "Parameter '" + rules.key() + "' must use one of its declared options");

        try {
            javaType(rules.javaType(), value, !rules.procedureTemplate());
        } catch (RuntimeException exception) {
            // Parsing exceptions can include the submitted value. Never retain or echo them.
            throw violation("INVALID_LITERAL", "Parameter '" + rules.key() + "' is not a valid " + rules.javaType());
        }
        return value;
    }

    public static void configuration(TemplateParameterRules rules, ConfigurationValueType type, boolean writable) {
        if (rules.writableBindingRequired() && !writable)
            throw violation("WRITABLE_BINDING_REQUIRED", "Parameter '" + rules.key() + "' requires a writable configuration");

        if (!ParameterValueTypeCompatibility.isConfigurationValueTypeCompatible(rules.javaType(), type))
            throw violation("VALUE_TYPE_MISMATCH", "Parameter '" + rules.key() + "' and its binding must both be binary or both be non-binary");

        if (!rules.allowedConfigurationValueTypes().isEmpty() && !rules.allowedConfigurationValueTypes().contains(type.name()))
            throw violation("VALUE_TYPE_NOT_ALLOWED", "Parameter '" + rules.key() + "' only accepts configurations of type " + rules.allowedConfigurationValueTypes());
    }

    public static void secret(TemplateParameterRules rules, SecretValueType type, boolean writable) {
        if (rules.writableBindingRequired() && !writable)
            throw violation("WRITABLE_BINDING_REQUIRED", "Parameter '" + rules.key() + "' requires a writable secret");

        if (!ParameterValueTypeCompatibility.isSecretValueTypeCompatible(rules.javaType(), type))
            throw violation("VALUE_TYPE_MISMATCH", "Parameter '" + rules.key() + "' and its binding must match the parameter's required secret type");

        if (!rules.allowedSecretValueTypes().isEmpty() && !rules.allowedSecretValueTypes().contains(type.name()))
            throw violation("VALUE_TYPE_NOT_ALLOWED", "Parameter '" + rules.key() + "' only accepts secrets of type " + rules.allowedSecretValueTypes());
    }

    private static void javaType(String type, String value, boolean alert) {
        switch (type) {
            case "byte", "java.lang.Byte" -> Byte.parseByte(value);
            case "short", "java.lang.Short" -> Short.parseShort(value);
            case "int", "java.lang.Integer" -> Integer.parseInt(value);
            case "long", "java.lang.Long" -> Long.parseLong(value);
            case "float", "java.lang.Float" -> Float.parseFloat(value);
            case "double", "java.lang.Double" -> Double.parseDouble(value);
            case "boolean", "java.lang.Boolean" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
                    throw new IllegalArgumentException();
            }
            case "char", "java.lang.Character" -> {
                if (value.length() != 1)
                    throw new IllegalArgumentException();
            }
            case "java.math.BigInteger" -> new BigInteger(value);
            case "java.math.BigDecimal" -> new BigDecimal(value);
            case "java.net.URI" -> URI.create(value);
            case "java.time.Duration" -> Duration.parse(value);
            case "java.time.Instant" -> Instant.parse(value);
            case "org.springframework.scheduling.support.CronExpression" -> {
                if (alert && CronExpression.parse(value).next(ZonedDateTime.now(ZoneId.systemDefault())) == null)
                    throw new IllegalArgumentException();
            }
            case "app.alertify.worker.contract.DatabaseCredentials" -> {
                if (alert)
                    DatabaseCredentials.fromJson(value);
            }
            default -> { }
        }
    }

    public static Violation violation(String code, String message) {
        return new Violation(code, message);
    }

    public static final class Violation extends IllegalArgumentException {
        private final String code;

        private Violation(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }
}
