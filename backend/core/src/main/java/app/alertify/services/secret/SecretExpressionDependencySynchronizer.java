package app.alertify.services.secret;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Service;

import app.alertify.api.error.InvalidConfigurationExpressionException;
import app.alertify.api.error.InvalidSecretValueException;
import app.alertify.configuration.service.ConfigurationExpressionParser;
import app.alertify.configuration.service.ConfigurationExpressionParser.ExpressionScope;
import app.alertify.configuration.service.ConfigurationExpressionParser.ParsedExpression;
import app.alertify.configuration.service.ConfigurationExpressionUtilityResolver;
import app.alertify.configuration.service.EnvironmentVariableResolver;
import app.alertify.configuration.service.ExpressionEvaluator;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;

/**
 * Rebuilds and validates the persisted dependency edges of a secret expression.
 * Kept separate from expression evaluation so the isolated backup/import tool
 * does not need to bootstrap the runtime evaluator and audit-log subsystem.
 */
@Service
public class SecretExpressionDependencySynchronizer {

    private static final int MAX_DEPTH = ExpressionEvaluator.MAX_DEPTH;

    private final ApplicationSecretRepository secretRepository;
    private final ApplicationConfigurationRepository configurationRepository;
    private final SecretExpressionDependencyRepository dependencyRepository;
    private final ConfigurationExpressionParser parser;
    private final EnvironmentVariableResolver environmentVariables;
    private final ConfigurationExpressionUtilityResolver utilities;

    public SecretExpressionDependencySynchronizer(ApplicationSecretRepository secretRepository,
            ApplicationConfigurationRepository configurationRepository,
            SecretExpressionDependencyRepository dependencyRepository, ConfigurationExpressionParser parser,
            EnvironmentVariableResolver environmentVariables, ConfigurationExpressionUtilityResolver utilities) {
        this.secretRepository = secretRepository;
        this.configurationRepository = configurationRepository;
        this.dependencyRepository = dependencyRepository;
        this.parser = parser;
        this.environmentVariables = environmentVariables;
        this.utilities = utilities;
    }

    public void synchronize(ApplicationSecret secret, String plaintext) {
        if (secret.getValueType() != SecretValueType.EXPRESSION) {
            dependencyRepository.replace(secret.getId(), Set.of(), Set.of());
            return;
        }

        ParsedExpression parsed = parse(plaintext);
        Set<Long> referencedSecretIds = referencedSecretIds(parsed);
        Set<Long> referencedConfigurationIds = referencedConfigurationIds(parsed);
        validateEnvironmentAndUtilities(parsed);

        dependencyRepository.replace(secret.getId(), referencedSecretIds, referencedConfigurationIds);
        ensureAcyclic(secret.getId(), new LinkedHashSet<>(), 0);
    }

    private ParsedExpression parse(String expression) {
        try {
            return parser.parse(expression, ExpressionScope.SECRET);
        } catch (InvalidConfigurationExpressionException exception) {
            throw new InvalidSecretValueException("EXPRESSION value is invalid: " + exception.getMessage());
        }
    }

    private Set<Long> referencedSecretIds(ParsedExpression parsed) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String name : parsed.secretNames()) {
            ApplicationSecret referenced = secretRepository.findByNameIgnoreCase(name).orElseThrow(
                    () -> new InvalidSecretValueException("EXPRESSION value is invalid: referenced secret '" + name + "' was not found")
            );
            ids.add(referenced.getId());
        }
        return ids;
    }

    private Set<Long> referencedConfigurationIds(ParsedExpression parsed) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String name : parsed.configurationNames()) {
            ApplicationConfiguration referenced = configurationRepository.findByNameIgnoreCase(name).orElseThrow(
                    () -> new InvalidSecretValueException("EXPRESSION value is invalid: referenced configuration '" + name + "' was not found")
            );
            ids.add(referenced.getId());
        }
        return ids;
    }

    private void validateEnvironmentAndUtilities(ParsedExpression parsed) {
        try {
            parsed.environmentNames().forEach(environmentVariables::ensureAllowed);
            parsed.utilityNames().forEach(name -> utilities.ensureSupported(name, false));
            parsed.utilityFunctionNames().forEach(name -> utilities.ensureSupported(name, true));
        } catch (InvalidConfigurationExpressionException exception) {
            throw new InvalidSecretValueException("EXPRESSION value is invalid: " + exception.getMessage());
        }
    }

    private void ensureAcyclic(Long secretId, Set<Long> path, int depth) {
        if (depth > MAX_DEPTH)
            throw new InvalidSecretValueException("EXPRESSION value is invalid: exceeds the maximum dependency depth of " + MAX_DEPTH);

        if (!path.add(secretId))
            throw new InvalidSecretValueException("EXPRESSION value is invalid: secret expression dependency cycle detected");

        try {
            for (Long referencedId : dependencyRepository.findReferencedSecretIds(secretId))
                ensureAcyclic(referencedId, path, depth + 1);
        } finally {
            path.remove(secretId);
        }
    }
}
