package app.alertify.configuration.service;

import java.util.Locale;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidConfigurationValueException;
import app.alertify.configuration.api.ConfigurationWarning;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;

final class SystemConfigurationPolicy {

    static final String KEY_PART = "KEY_PART";
    private static final Pattern KEY_PART_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    private SystemConfigurationPolicy() {
    }

    static boolean isSystemManaged(String name) {
        return KEY_PART.equalsIgnoreCase(name);
    }

    static boolean isDeletable(String name) {
        return !isSystemManaged(name);
    }

    static ConfigurationWarning warning(String name) {
        return isSystemManaged(name) ? ConfigurationWarning.SECRET_LOSS : null;
    }

    static void validateCreation(String name) {
        if (isSystemManaged(name)) {
            throw new ConflictException(
                "Configuration '" + KEY_PART + "' is created automatically and cannot be created manually"
            );
        }
    }

    static void validateUpdate(
            ApplicationConfiguration configuration,
            String requestedName,
            ConfigurationValueType requestedType,
            JsonNode requestedValue) {
        if (!isSystemManaged(configuration.getName())) return;

        if (!KEY_PART.equals(requestedName)) {
            throw new ConflictException("Configuration '" + KEY_PART + "' cannot be renamed");
        }
        if (requestedType != ConfigurationValueType.STRING) {
            throw new InvalidConfigurationValueException(
                "Configuration '" + KEY_PART + "' must have type STRING"
            );
        }

        String value = requestedValue.stringValue();
        if (value == null || !KEY_PART_PATTERN.matcher(value).matches()) {
            throw new InvalidConfigurationValueException(
                "Configuration '" + KEY_PART + "' must contain exactly 64 hexadecimal characters"
            );
        }
    }

    static String normalizeKeyPart(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    static void validateDeletion(ApplicationConfiguration configuration) {
        if (!isDeletable(configuration.getName())) {
            throw new ConflictException("Configuration '" + KEY_PART + "' cannot be deleted");
        }
    }
}
