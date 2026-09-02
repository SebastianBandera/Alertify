package app.alertify.configuration.service;

import tools.jackson.databind.JsonNode;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidConfigurationValueException;
import app.alertify.configuration.api.ConfigurationWarning;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;

/**
 * Central security policy for system-managed configurations. It protects
 * {@code KEY_PART} from creation, deletion, renaming, export and value
 * disclosure while allowing controlled replacement of its value.
 */
final class SystemConfigurationPolicy {

    static final String KEY_PART = "KEY_PART";

    private SystemConfigurationPolicy() {
    }

    static boolean isSystemManaged(String name) {
        return KEY_PART.equalsIgnoreCase(name);
    }

    static boolean isDeletable(String name) {
        return !isSystemManaged(name);
    }

    static boolean isValueHidden(String name) {
        return KEY_PART.equalsIgnoreCase(name);
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

    static void validateUpdate(ApplicationConfiguration configuration, String requestedName, ConfigurationValueType requestedType, JsonNode requestedValue) {
        if (!isSystemManaged(configuration.getName()))
            return;

        if (!KEY_PART.equals(requestedName)) {
            throw new ConflictException("Configuration '" + KEY_PART + "' cannot be renamed");
        }
        if (requestedType != ConfigurationValueType.STRING) {
            throw new InvalidConfigurationValueException(
                    "Configuration '" + KEY_PART + "' must have type STRING"
            );
        }

        String value = requestedValue.stringValue();
        if (value == null || value.isEmpty()) {
            throw new InvalidConfigurationValueException(
                    "Configuration '" + KEY_PART + "' must contain at least one character"
            );
        }
    }

    static void validateWritable(String name, boolean writable) {
        if (writable && isSystemManaged(name))
            throw new ConflictException("Configuration '" + KEY_PART + "' cannot be writable by alerts");
    }

    static void validateDeletion(ApplicationConfiguration configuration) {
        if (!isDeletable(configuration.getName())) {
            throw new ConflictException("Configuration '" + KEY_PART + "' cannot be deleted");
        }
    }
}
