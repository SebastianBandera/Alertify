package app.alertify.services.secret;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidConfigurationExpressionException;
import app.alertify.api.error.InvalidSecretValueException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.service.ConfigurationExpressionParser;
import app.alertify.configuration.service.ConfigurationExpressionParser.ExpressionScope;
import app.alertify.configuration.service.ConfigurationExpressionParser.ParsedExpression;
import app.alertify.configuration.service.ConfigurationExpressionService;
import app.alertify.configuration.service.ConfigurationExpressionUtilityResolver;
import app.alertify.configuration.service.EnvironmentVariableResolver;
import app.alertify.configuration.service.ExpressionEvaluator;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.secret.api.SecretExpressionSuggestionsResponse;

/**
 * Resolves secret values on access. Plain secrets are just decrypted;
 * {@code EXPRESSION} secrets are decrypted and evaluated, reading other
 * secrets, configurations, environment variables and utilities. Keeps the
 * persisted dependency edges acyclic and blocks renames or deletions of
 * referenced secrets. The evaluated value never leaves the backend.
 */
@Service
public class SecretExpressionService {

    private static final int MAX_DEPTH = ExpressionEvaluator.MAX_DEPTH;

    private final ApplicationSecretRepository secretRepository;
    private final ApplicationConfigurationRepository configurationRepository;
    private final SecretExpressionDependencyRepository dependencyRepository;
    private final SecretEncryptionService encryptionService;
    private final ConfigurationExpressionParser parser;
    private final ConfigurationExpressionService configurationExpressionService;
    private final EnvironmentVariableResolver environmentVariables;
    private final ConfigurationExpressionUtilityResolver utilities;
    private final ApplicationEventLogger eventLogger;

    public SecretExpressionService(ApplicationSecretRepository secretRepository, ApplicationConfigurationRepository configurationRepository, SecretExpressionDependencyRepository dependencyRepository, SecretEncryptionService encryptionService, ConfigurationExpressionParser parser, ConfigurationExpressionService configurationExpressionService, EnvironmentVariableResolver environmentVariables, ConfigurationExpressionUtilityResolver utilities, ApplicationEventLogger eventLogger) {
        this.secretRepository = secretRepository;
        this.configurationRepository = configurationRepository;
        this.dependencyRepository = dependencyRepository;
        this.encryptionService = encryptionService;
        this.parser = parser;
        this.configurationExpressionService = configurationExpressionService;
        this.environmentVariables = environmentVariables;
        this.utilities = utilities;
        this.eventLogger = eventLogger;
    }

    /** Decrypts the secret and, for expression secrets, evaluates it. */
    @Transactional(readOnly = true)
    public String resolve(ApplicationSecret secret) {
        return resolveSecret(secret, new LinkedHashSet<>(), 0, utilities.snapshot());
    }

    @Transactional(readOnly = true)
    public SecretExpressionSuggestionsResponse suggestions() {
        List<String> secrets = secretRepository.findAll().stream()
                .map(ApplicationSecret::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return new SecretExpressionSuggestionsResponse(
                configurationRepository.findAllNames(), secrets, environmentVariables.allowedNames(),
                utilities.names(), utilities.functionNames()
        );
    }

    /**
     * Checks a draft expression the same way saving would: syntax, that every
     * referenced secret and configuration exists, and that the draft does not
     * create a cycle through the secret it belongs to.
     */
    @Transactional(readOnly = true)
    public void validateDraft(Long secretId, String name, String expression) {
        ParsedExpression parsed = parse(expression);
        Set<Long> referencedSecretIds = referencedSecretIds(parsed);
        referencedConfigurationIds(parsed);
        validateEnvironmentAndUtilities(parsed);

        Set<String> path = new LinkedHashSet<>();
        if (name != null && !name.isBlank())
            path.add(normalizedKey(name.trim()));

        if (secretId != null)
            secretRepository.findById(secretId).ifPresent(secret -> path.add(normalizedKey(secret.getName())));

        for (Long referencedId : referencedSecretIds) {
            ApplicationSecret referenced = secretRepository.findById(referencedId).orElseThrow();
            if (path.contains(normalizedKey(referenced.getName())))
                throw new InvalidSecretValueException("EXPRESSION value is invalid: secret expression cycle detected at '" + referenced.getName() + "'");

            ensureAcyclicFrom(referencedId, path, referenced.getName());
        }
        eventLogger.success("SECRET_EXPRESSION_VALIDATED", Map.of(
                "secretReferenceCount", parsed.secretNames().size(),
                "configurationReferenceCount", parsed.configurationNames().size()
        ));
    }

    /** Persists the dependency edges of a saved secret from its plaintext, validating references and cycles. */
    public void synchronizeDependencies(ApplicationSecret secret, String plaintext) {
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

    public void ensureNotReferenced(ApplicationSecret secret, String operation) {
        List<String> dependents = dependencyRepository.findDependentSecretNames(secret.getId());
        if (dependents.isEmpty())
            return;

        throw new ConflictException(
                "SECRET_REFERENCED_BY_EXPRESSION",
                "Secret '" + secret.getName() + "' cannot be " + operation + " because it is referenced by: " + ConfigurationExpressionService.summarize(dependents),
                Map.of("secretName", secret.getName(), "dependentCount", String.valueOf(dependents.size()))
        );
    }

    private String resolveSecret(ApplicationSecret secret, Set<String> path, int depth, ZonedDateTime now) {
        String key = normalizedKey(secret.getName());
        if (depth > MAX_DEPTH)
            throw new InvalidConfigurationExpressionException("Secret expression exceeds the maximum resolution depth of " + MAX_DEPTH);

        if (!path.add(key))
            throw new InvalidConfigurationExpressionException("Secret expression cycle detected at '" + secret.getName() + "'");

        try {
            String plaintext = encryptionService.decrypt(secret);
            if (secret.getValueType() != SecretValueType.EXPRESSION)
                return ExpressionEvaluator.checkedValue(plaintext, secret.getName());

            ParsedExpression parsed = parser.parse(plaintext, ExpressionScope.SECRET);
            return ExpressionEvaluator.evaluate(parsed, (reference, argument, currentDepth) -> switch (reference.type()) {
                case SECRET -> resolveSecretReference(reference.name(), path, currentDepth + 1, now);
                case CONFIGURATION -> configurationExpressionService.getResolvedValueByName(reference.name());
                case ENVIRONMENT -> environmentVariables.resolve(reference.name());
                case UTILITY -> reference.isFunction()
                        ? utilities.apply(reference.name(), argument)
                        : utilities.resolve(reference.name(), now);
            }, depth + 1);
        } finally {
            path.remove(key);
        }
    }

    private String resolveSecretReference(String name, Set<String> path, int depth, ZonedDateTime now) {
        ApplicationSecret referenced = secretRepository.findByNameIgnoreCase(name).orElseThrow(
                () -> new ResourceNotFoundException("Secret '" + name + "' was not found")
        );
        String value = resolveSecret(referenced, path, depth, now);
        eventLogger.success("SECRET_VALUE_ACCESSED", Map.of("secretId", referenced.getId(), "name", referenced.getName(), "reason", "EXPRESSION_REFERENCE"));
        return value;
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

    /** Walks the persisted graph from {@code secretId} checking that no node is in {@code namePath}. */
    private void ensureAcyclicFrom(Long secretId, Set<String> namePath, String displayName) {
        Set<Long> visited = new LinkedHashSet<>();
        walk(secretId, namePath, visited, 0, displayName);
    }

    private void walk(Long secretId, Set<String> namePath, Set<Long> visited, int depth, String displayName) {
        if (depth > MAX_DEPTH)
            throw new InvalidSecretValueException("EXPRESSION value is invalid: exceeds the maximum dependency depth of " + MAX_DEPTH);

        if (!visited.add(secretId))
            return;

        for (Long referencedId : dependencyRepository.findReferencedSecretIds(secretId)) {
            ApplicationSecret referenced = secretRepository.findById(referencedId).orElseThrow();
            if (namePath.contains(normalizedKey(referenced.getName())))
                throw new InvalidSecretValueException("EXPRESSION value is invalid: secret expression cycle detected at '" + displayName + "'");

            walk(referencedId, namePath, visited, depth + 1, displayName);
        }
    }

    private static String normalizedKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
