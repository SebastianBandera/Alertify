package app.alertify.alerts.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.GitCredentials;

/**
 * Single source of truth for how a template parameter's java type constrains
 * which {@link ConfigurationValueType}/{@link SecretValueType} it may bind
 * to. A java type such as {@code byte[]}, {@link DatabaseCredentials} or
 * {@link GitCredentials} has a physically required value type (the worker only
 * knows how to convert that exact pairing); every other java type accepts any
 * value type except those
 * reserved ones, unless the template further narrows the choice with an
 * explicit, optional {@code allowedConfigurationValueTypes}/
 * {@code allowedSecretValueTypes} declaration.
 */
public final class ParameterValueTypeCompatibility {

    private static final String BYTE_ARRAY_JAVA_TYPE = byte[].class.getName();
    private static final String DATABASE_CREDENTIALS_JAVA_TYPE = DatabaseCredentials.class.getName();
    private static final String GIT_CREDENTIALS_JAVA_TYPE = GitCredentials.class.getName();

    private ParameterValueTypeCompatibility() {
    }

    public static Optional<ConfigurationValueType> requiredConfigurationValueType(String javaType) {
        return BYTE_ARRAY_JAVA_TYPE.equals(javaType) ? Optional.of(ConfigurationValueType.BINARY) : Optional.empty();
    }

    public static Optional<SecretValueType> requiredSecretValueType(String javaType) {
        if (BYTE_ARRAY_JAVA_TYPE.equals(javaType))
            return Optional.of(SecretValueType.BINARY);

        if (DATABASE_CREDENTIALS_JAVA_TYPE.equals(javaType))
            return Optional.of(SecretValueType.DB_SECRET);

        if (GIT_CREDENTIALS_JAVA_TYPE.equals(javaType))
            return Optional.of(SecretValueType.GIT_SECRET);

        return Optional.empty();
    }

    public static boolean isConfigurationValueTypeCompatible(String javaType, ConfigurationValueType valueType) {
        return requiredConfigurationValueType(javaType)
            .map(required -> required == valueType)
            .orElse(valueType != ConfigurationValueType.BINARY);
    }

    public static boolean isSecretValueTypeCompatible(String javaType, SecretValueType valueType) {
        return requiredSecretValueType(javaType)
            .map(required -> required == valueType)
            .orElse(valueType != SecretValueType.BINARY && valueType != SecretValueType.DB_SECRET
                && valueType != SecretValueType.GIT_SECRET);
    }

    /**
     * Resolves the effective list of configuration value types a parameter of
     * the given java type may bind to: the declared list when it is consistent
     * with the java type's physical requirement, or that requirement alone when
     * nothing was declared. Fails fast when the declaration contradicts the java
     * type (wrong names, or a requirement it cannot satisfy).
     */
    public static List<String> effectiveAllowedConfigurationValueTypes(String javaType, List<String> declared, String description) {
        List<String> names = validNames(declared, ConfigurationValueType.class, "allowedConfigurationValueTypes", description);
        Optional<ConfigurationValueType> required = requiredConfigurationValueType(javaType);
        if (required.isPresent()) {
            if (!names.isEmpty() && !names.equals(List.of(required.get().name()))) {
                throw new IllegalStateException(
                    "allowedConfigurationValueTypes must be exactly [" + required.get() + "] for java type "
                        + javaType + ": " + description
                );
            }
            return List.of(required.get().name());
        }
        if (names.contains(ConfigurationValueType.BINARY.name())) {
            throw new IllegalStateException(
                "allowedConfigurationValueTypes must not include BINARY unless the java type is byte[]: " + description
            );
        }
        return names;
    }

    /**
     * Same resolution as {@link #effectiveAllowedConfigurationValueTypes} but for
     * secret value types, where {@code BINARY}, {@code DB_SECRET} and
     * {@code GIT_SECRET} are structured pairings that are not meaningful as free
     * text, so they may only be bound by a java type that requires them.
     */
    public static List<String> effectiveAllowedSecretValueTypes(String javaType, List<String> declared, String description) {
        List<String> names = validNames(declared, SecretValueType.class, "allowedSecretValueTypes", description);
        Optional<SecretValueType> required = requiredSecretValueType(javaType);
        if (required.isPresent()) {
            if (!names.isEmpty() && !names.equals(List.of(required.get().name()))) {
                throw new IllegalStateException(
                    "allowedSecretValueTypes must be exactly [" + required.get() + "] for java type "
                        + javaType + ": " + description
                );
            }
            return List.of(required.get().name());
        }
        if (names.contains(SecretValueType.BINARY.name()) || names.contains(SecretValueType.DB_SECRET.name())
                || names.contains(SecretValueType.GIT_SECRET.name())) {
            throw new IllegalStateException(
                "allowedSecretValueTypes must not include BINARY, DB_SECRET or GIT_SECRET unless the java type requires them: " + description
            );
        }
        return names;
    }

    /**
     * A parameter whose java type has a physically required configuration or
     * secret value type (BINARY, DB_SECRET) has no meaningful free-text
     * representation today, so {@code TEXT} must not be one of its allowed
     * sources.
     */
    public static void validateAllowedSourcesForRequiredTypes(String javaType, Set<AlertParameterSource> allowedSources, String description) {
        boolean requiresSpecialType = requiredConfigurationValueType(javaType).isPresent()
            || requiredSecretValueType(javaType).isPresent();
        if (requiresSpecialType && allowedSources.contains(AlertParameterSource.TEXT)) {
            throw new IllegalStateException(
                "allowedSources must not include TEXT for java type " + javaType
                    + " because it requires a binary or database-secret binding: " + description
            );
        }
    }

    private static <E extends Enum<E>> List<String> validNames(List<String> declared, Class<E> enumType, String attribute, String description) {
        if (declared.isEmpty())
            return List.of();

        Set<String> validNames = Stream.of(enumType.getEnumConstants()).map(Enum::name).collect(java.util.stream.Collectors.toSet());
        List<String> names = new ArrayList<>(declared);
        for (String name : names) {
            if (!validNames.contains(name)) {
                throw new IllegalStateException(
                    attribute + " contains an unknown " + enumType.getSimpleName() + " name '" + name + "': " + description
                );
            }
        }
        return List.copyOf(names);
    }
}
