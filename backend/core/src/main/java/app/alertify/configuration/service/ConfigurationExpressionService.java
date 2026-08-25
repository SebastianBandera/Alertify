package app.alertify.configuration.service;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidConfigurationExpressionException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.api.ConfigurationExpressionEvaluationRequest;
import app.alertify.configuration.api.ConfigurationExpressionEvaluationResponse;
import app.alertify.configuration.api.ConfigurationExpressionSuggestionsResponse;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.logging.ApplicationEventLogger;
import tools.jackson.databind.JsonNode;

/**
 * Evaluates expression configurations on demand, resolves nested references,
 * enforces depth and output limits, and keeps the persisted dependency graph
 * synchronized and acyclic.
 */
@Service
public class ConfigurationExpressionService {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_RESULT_BYTES = 1024 * 1024;

    private final ApplicationConfigurationRepository configurationRepository;
    private final ConfigurationExpressionDependencyRepository dependencyRepository;
    private final ConfigurationExpressionParser parser;
    private final EnvironmentVariableResolver environmentVariables;
    private final ConfigurationExpressionUtilityResolver utilities;
    private final ApplicationEventLogger eventLogger;

    public ConfigurationExpressionService(ApplicationConfigurationRepository configurationRepository, ConfigurationExpressionDependencyRepository dependencyRepository, ConfigurationExpressionParser parser, EnvironmentVariableResolver environmentVariables, ConfigurationExpressionUtilityResolver utilities, ApplicationEventLogger eventLogger) {
        this.configurationRepository = configurationRepository;
        this.dependencyRepository = dependencyRepository;
        this.parser = parser;
        this.environmentVariables = environmentVariables;
        this.utilities = utilities;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public String getResolvedValueByName(String name) {
        ApplicationConfiguration configuration = findByName(name);
        return resolveConfiguration(configuration, null, new LinkedHashSet<>(), 0, utilities.snapshot());
    }

    @Transactional(readOnly = true)
    public ConfigurationExpressionEvaluationResponse evaluate(ConfigurationExpressionEvaluationRequest request) {
        DraftExpression draft = new DraftExpression(
                normalizeOptional(request.configurationName()), request.expression()
        );
        ConfigurationExpressionParser.ParsedExpression parsed = parser.parse(request.expression());
        String value = resolveExpression(parsed, draft, new LinkedHashSet<>(), 0, utilities.snapshot());

        Map<String, Object> data = new LinkedHashMap<>();
        if (request.configurationId() != null)
            data.put("configurationId", request.configurationId());
        if (draft.name() != null)
            data.put("name", draft.name());
        data.put("configurationReferenceCount", parsed.configurationNames().size());
        data.put("environmentReferenceCount", parsed.environmentNames().size());
        data.put("utilityReferenceCount", parsed.utilityNames().size());
        eventLogger.success("CONFIGURATION_EXPRESSION_EVALUATED", data);
        return new ConfigurationExpressionEvaluationResponse(value);
    }

    @Transactional(readOnly = true)
    public ConfigurationExpressionSuggestionsResponse suggestions() {
        List<String> configurations = configurationRepository.findAllNames().stream()
                .filter(name -> !SystemConfigurationPolicy.isValueHidden(name))
                .toList();
        return new ConfigurationExpressionSuggestionsResponse(
                configurations, environmentVariables.allowedNames(), utilities.names()
        );
    }

    void synchronizeDependencies(ApplicationConfiguration configuration) {
        if (configuration.getValueType() != ConfigurationValueType.EXPRESSION) {
            dependencyRepository.replace(configuration.getId(), Set.of());
            return;
        }

        ConfigurationExpressionParser.ParsedExpression parsed = parser.parse(configuration.getValue().stringValue());
        Set<Long> referencedIds = new LinkedHashSet<>();
        for (String name : parsed.configurationNames()) {
            ApplicationConfiguration referenced = configurationRepository.findByNameIgnoreCase(name).orElseThrow(
                    () -> new InvalidConfigurationExpressionException("Referenced configuration '" + name + "' was not found")
            );
            referencedIds.add(referenced.getId());
        }
        parsed.environmentNames().forEach(environmentVariables::ensureAllowed);
        parsed.utilityNames().forEach(utilities::ensureSupported);

        dependencyRepository.replace(configuration.getId(), referencedIds);
        ensureAcyclic(configuration.getId(), new LinkedHashSet<>(), 0);
    }

    void ensureNotReferenced(ApplicationConfiguration configuration, String operation) {
        List<String> dependents = dependencyRepository.findDependentNames(configuration.getId());
        if (dependents.isEmpty())
            return;

        String names = String.join(", ", dependents.subList(0, Math.min(dependents.size(), 5)));
        if (dependents.size() > 5)
            names += ", ...";
        throw new ConflictException(
                "CONFIGURATION_REFERENCED_BY_EXPRESSION",
                "Configuration '" + configuration.getName() + "' cannot be " + operation + " because it is referenced by: " + names,
                Map.of("configurationName", configuration.getName(), "dependentCount", String.valueOf(dependents.size()))
        );
    }

    private String resolveConfiguration(ApplicationConfiguration configuration, DraftExpression draft, Set<String> path, int depth, ZonedDateTime now) {
        if (SystemConfigurationPolicy.isValueHidden(configuration.getName())) {
            throw new InvalidConfigurationExpressionException(
                    "Configuration '" + SystemConfigurationPolicy.KEY_PART + "' cannot be referenced by expressions"
            );
        }

        String key = normalizedKey(configuration.getName());
        enter(path, key, configuration.getName(), depth);
        try {
            if (configuration.getValueType() == ConfigurationValueType.EXPRESSION) {
                return resolveExpression(parser.parse(configuration.getValue().stringValue()), draft, path, depth + 1, now);
            }
            return checkedValue(configurationValueAsString(configuration.getValue()), configuration.getName());
        } finally {
            path.remove(key);
        }
    }

    private String resolveExpression(ConfigurationExpressionParser.ParsedExpression parsed, DraftExpression draft, Set<String> path, int depth, ZonedDateTime now) {
        if (depth > MAX_DEPTH)
            throw new InvalidConfigurationExpressionException("Configuration expression exceeds the maximum resolution depth of " + MAX_DEPTH);

        StringBuilder result = new StringBuilder();
        int cursor = 0;
        for (ConfigurationExpressionParser.ExpressionReference reference : parsed.references()) {
            appendChecked(result, parsed.source().substring(cursor, reference.start()));
            String value = switch (reference.type()) {
                case CONFIGURATION -> resolveConfigurationReference(reference.name(), draft, path, depth, now);
                case ENVIRONMENT -> environmentVariables.resolve(reference.name());
                case UTILITY -> utilities.resolve(reference.name(), now);
            };
            appendChecked(result, value);
            cursor = reference.end();
        }
        appendChecked(result, parsed.source().substring(cursor));
        return checkedValue(result.toString(), "expression");
    }

    private String resolveConfigurationReference(String name, DraftExpression draft, Set<String> path, int depth, ZonedDateTime now) {
        if (draft != null && draft.name() != null && draft.name().equalsIgnoreCase(name)) {
            String key = normalizedKey(draft.name());
            enter(path, key, draft.name(), depth);
            try {
                return resolveExpression(parser.parse(draft.expression()), draft, path, depth + 1, now);
            } finally {
                path.remove(key);
            }
        }
        return resolveConfiguration(findByName(name), draft, path, depth, now);
    }

    private void ensureAcyclic(Long configurationId, Set<Long> path, int depth) {
        if (depth > MAX_DEPTH)
            throw new InvalidConfigurationExpressionException("Configuration expression exceeds the maximum dependency depth of " + MAX_DEPTH);
        if (!path.add(configurationId))
            throw new InvalidConfigurationExpressionException("Configuration expression dependency cycle detected");
        try {
            for (Long referencedId : dependencyRepository.findReferencedIds(configurationId))
                ensureAcyclic(referencedId, path, depth + 1);
        } finally {
            path.remove(configurationId);
        }
    }

    private ApplicationConfiguration findByName(String name) {
        return configurationRepository.findByNameIgnoreCase(name).orElseThrow(
                () -> new ResourceNotFoundException("Configuration '" + name + "' was not found")
        );
    }

    private static void enter(Set<String> path, String key, String displayName, int depth) {
        if (depth > MAX_DEPTH)
            throw new InvalidConfigurationExpressionException("Configuration expression exceeds the maximum resolution depth of " + MAX_DEPTH);
        if (!path.add(key))
            throw new InvalidConfigurationExpressionException("Configuration expression cycle detected at '" + displayName + "'");
    }

    private static String configurationValueAsString(JsonNode value) {
        return value.isString() ? value.stringValue() : value.toString();
    }

    private static void appendChecked(StringBuilder result, String value) {
        if (result.length() + value.length() > MAX_RESULT_BYTES) {
            throw new InvalidConfigurationExpressionException("Evaluated configuration exceeds the 1 MiB result limit");
        }
        result.append(value);
    }

    private static String checkedValue(String value, String source) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw new InvalidConfigurationExpressionException(
                    "Evaluated configuration from '" + source + "' exceeds the 1 MiB result limit"
            );
        }
        return value;
    }

    private static String normalizedKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String normalizeOptional(String value) {
        if (value == null)
            return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record DraftExpression(
        String name,
        String expression
    ) {
    }
}
