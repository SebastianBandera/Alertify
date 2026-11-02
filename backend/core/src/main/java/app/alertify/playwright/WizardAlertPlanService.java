package app.alertify.playwright;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.ParameterValueTypeCompatibility;
import app.alertify.configuration.service.ConfigurationExpressionService;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.services.secret.SecretAccessContext;
import app.alertify.services.secret.SecretAccessService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Imports definitions and reads references, never updates an alert or a shared resource. */
@Service
public class WizardAlertPlanService {
    static final String TEMPLATE = "app.alertify.alerts.templates.PlaywrightPageAlertTemplate";
    static final Set<String> BINDABLE = Set.of("url", "steps", "loadTimeoutSeconds", "elementTimeoutSeconds");
    private final AlertRepository alerts;
    private final AlertTemplateParameterDefinitionRepository definitions;
    private final ApplicationConfigurationRepository configurations;
    private final ApplicationSecretRepository secrets;
    private final ProcedureRepository procedures;
    private final ConfigurationExpressionService expressions;
    private final SecretAccessService access;
    private final JsonMapper json;

    public WizardAlertPlanService(AlertRepository alerts, AlertTemplateParameterDefinitionRepository definitions, ApplicationConfigurationRepository configurations, ApplicationSecretRepository secrets, ProcedureRepository procedures, ConfigurationExpressionService expressions, SecretAccessService access, JsonMapper json) {
        this.alerts = alerts;
        this.definitions = definitions;
        this.configurations = configurations;
        this.secrets = secrets;
        this.procedures = procedures;
        this.expressions = expressions;
        this.access = access;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public ObjectNode create(JsonNode payload) {
        var alert = alerts.findById(payload.path("alertId").asLong()).orElseThrow(() -> new IllegalArgumentException("Alert no longer exists"));
        if (!TEMPLATE.equals(alert.getTemplate().getTemplateKey()))
            throw new IllegalArgumentException("Only Playwright alerts can be edited in this assistant");

        if (alert.getVersion() != payload.path("version").asLong(-1))
            throw new IllegalArgumentException("The alert changed. Reload it before opening the assistant.");

        JsonNode form = payload.path("form");
        if (!form.isObject() || form.path("templateId").asLong() != alert.getTemplate().getId() || json.writeValueAsString(form).length() > 150_000)
            throw new IllegalArgumentException("Invalid alert editor snapshot");

        Map<String, AlertTemplateParameterDefinition> metadata = new LinkedHashMap<>();
        definitions.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(alert.getTemplate().getId()).forEach(value -> metadata.put(value.getParameterKey(), value));
        var settings = json.createObjectNode();
        var bindings = json.createObjectNode();
        Map<String, String> values = new LinkedHashMap<>();
        for (var entry : metadata.entrySet()) {
            String key = entry.getKey();
            var definition = entry.getValue();
            JsonNode parameter = form.path("parameters").path(key);
            String source = parameter.path("source").asString();
            if (!parameter.path("configured").asBoolean()) {
                values.put(key, definition.getDefaultValue());
                continue;
            }
            if (source.equals("TEXT") || source.equals("OPTION")) {
                values.put(key, parameter.path("textValue").asString());
                continue;
            }
            if (!BINDABLE.contains(key) || !definition.isBindingAllowed())
                throw new IllegalArgumentException("This parameter does not support linked values: " + key);

            if (definition.getAllowedSources().stream().noneMatch(value -> value.name().equals(source)))
                throw new IllegalArgumentException("This parameter does not allow the selected source: " + key);

            long resourceId = parameter.path(switch (source) {
                case "CONFIGURATION" -> "configurationId";
                case "SECRET" -> "secretId";
                case "PROCEDURE" -> "procedureId";
                default -> throw new IllegalArgumentException("Unsupported parameter source");
            }).asLong();
            ObjectNode binding = json.createObjectNode().put("source", source).put("id", resourceId);
            describe(binding, definition.getJavaType());
            bindings.set(key, binding);
            values.put(key, key.equals("url") || key.equals("steps") ? "" : definition.getDefaultValue());
        }
        settings.put("url", PlaywrightWizardService.normalizeUrl(required(values, "url")));
        settings.put("steps", required(values, "steps"));
        settings.put("loadTimeoutSeconds", integer(required(values, "loadTimeoutSeconds")));
        settings.put("elementTimeoutSeconds", integer(required(values, "elementTimeoutSeconds")));
        var browsers = json.createArrayNode();
        for (String engine : List.of("chromium", "firefox", "webkit")) {
            String enabled = required(values, engine + "Enabled");
            if (!List.of("true", "false").contains(enabled))
                throw new IllegalArgumentException("Invalid browser selection");

            if (enabled.equals("true")) browsers.add(engine);
        }
        if (browsers.isEmpty())
            throw new IllegalArgumentException("Select at least one browser before opening the assistant");

        settings.set("browsers", browsers);
        settings.put("engine", browsers.get(0).asString()).put("mode", "ASK");
        var document = json.createObjectNode();
        document.set("settings", settings);
        document.set("parameterBindings", bindings);
        document.set("sourceAlert", json.createObjectNode().put("id", alert.getId()).put("version", alert.getVersion()).set("form", form.deepCopy()));
        document.set("messages", json.createArrayNode());
        document.set("allowedProcedureIds", json.createArrayNode());
        return document;
    }

    @Transactional(readOnly = true)
    public String stamp(JsonNode document) {
        var stamp = json.createObjectNode();
        for (String key : BINDABLE.stream().sorted().toList()) {
            JsonNode binding = document.path("parameterBindings").path(key);
            if (binding.isObject()) stamp.set(key, describe((ObjectNode) binding.deepCopy(), key.equals("url") || key.equals("steps") ? "java.lang.String" : "int"));
        }
        return json.writeValueAsString(stamp);
    }

    @Transactional(readOnly = true)
    public String resolve(JsonNode binding, SecretAccessContext context) {
        describe((ObjectNode) binding.deepCopy(), "");
        return switch (binding.path("source").asString()) {
            case "CONFIGURATION" -> expressions.getResolvedValueByName(configurations.findById(binding.path("id").asLong()).orElseThrow().getName());
            case "SECRET" -> access.getValueByName(secrets.findById(binding.path("id").asLong()).orElseThrow().getName(), context);
            default -> throw new IllegalArgumentException("Procedure parameters require explicit confirmation");
        };
    }

    private ObjectNode describe(ObjectNode binding, String javaType) {
        long id = binding.path("id").asLong();
        switch (binding.path("source").asString()) {
            case "CONFIGURATION" -> {
                var resource = configurations.findById(id).orElseThrow(() -> new IllegalArgumentException("Linked configuration no longer exists"));
                if (resource.getValueType().name().equals("BINARY") || !javaType.isEmpty() && !ParameterValueTypeCompatibility.isConfigurationValueTypeCompatible(javaType, resource.getValueType())) throw new IllegalArgumentException("Incompatible linked configuration type");

                binding.put("name", resource.getName()).put("version", resource.getVersion());
            }
            case "SECRET" -> {
                var resource = secrets.findById(id).orElseThrow(() -> new IllegalArgumentException("Linked secret no longer exists"));
                if (!List.of("STRING", "EXPRESSION").contains(resource.getValueType().name()) || !javaType.isEmpty() && !ParameterValueTypeCompatibility.isSecretValueTypeCompatible(javaType, resource.getValueType())) throw new IllegalArgumentException("Incompatible linked secret type");

                binding.put("name", resource.getName()).put("version", resource.getVersion());
            }
            case "PROCEDURE" -> {
                var resource = procedures.findById(id).orElseThrow(() -> new IllegalArgumentException("Linked procedure no longer exists"));
                if (!resource.isEnabled()) throw new IllegalArgumentException("Linked procedure is disabled");

                binding.put("name", resource.getName()).put("version", resource.getVersion());
            }
            default -> throw new IllegalArgumentException("Unsupported parameter source");
        }
        return binding;
    }

    static void validateLocked(JsonNode document, PlaywrightWizardService.Settings next) {
        JsonNode previous = document.path("settings");
        for (String key : BINDABLE) {
            if (!document.path("parameterBindings").path(key).isObject()) continue;

            boolean equal = switch (key) {
                case "url" -> next.url().equals(previous.path(key).asString());
                case "steps" -> next.steps().equals(previous.path(key).asString());
                case "loadTimeoutSeconds" -> next.loadTimeoutSeconds() == previous.path(key).asInt();
                default -> next.elementTimeoutSeconds() == previous.path(key).asInt();
            };
            if (!equal) throw new IllegalArgumentException("Edit linked parameters in their original resource: " + key);
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) throw new IllegalArgumentException("Missing Playwright parameter: " + key);

        return value;
    }

    static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("A linked timeout must resolve to an integer"); }
    }
}
