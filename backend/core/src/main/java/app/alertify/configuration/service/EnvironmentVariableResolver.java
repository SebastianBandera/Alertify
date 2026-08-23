package app.alertify.configuration.service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import app.alertify.api.error.InvalidConfigurationExpressionException;

@Component
class EnvironmentVariableResolver {

    private static final Set<String> ALWAYS_DENIED = Set.of("KEY_ENV_PART");

    private final Set<String> allowedNames;

    EnvironmentVariableResolver(@Value("${configuration-expressions.allowed-environment-variables:}") String allowedNames) {
        Set<String> parsedNames = new LinkedHashSet<>();
        Arrays.stream(allowedNames.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .filter(name -> !isAlwaysDenied(name))
                .forEach(parsedNames::add);
        this.allowedNames = Set.copyOf(parsedNames);
    }

    String resolve(String name) {
        ensureAllowed(name);
        String value = System.getenv(name);
        if (value == null) {
            throw new InvalidConfigurationExpressionException(
                    "Environment variable '" + name + "' is allowed but is not defined"
            );
        }
        return value;
    }

    void ensureAllowed(String name) {
        if (!allowedNames.contains(name)) {
            throw new InvalidConfigurationExpressionException(
                    "Environment variable '" + name + "' is not allowed in configuration expressions"
            );
        }
    }

    List<String> allowedNames() {
        return allowedNames.stream().sorted(Comparator.naturalOrder()).toList();
    }

    static boolean isAlwaysDenied(String name) {
        return ALWAYS_DENIED.contains(name);
    }
}
