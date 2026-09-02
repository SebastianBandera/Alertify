package app.alertify.configuration.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.grpc.WritableConfigurationValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

/**
 * Applies changed alert parameter values only to configurations that remain
 * writable when the execution completes.
 */
@Service
public class WritableConfigurationService {

    private final ApplicationConfigurationRepository configurationRepository;
    private final ConfigurationValueValidator valueValidator;
    private final ConfigurationExpressionService expressionService;
    private final ConfigurationCacheInvalidator cacheInvalidator;
    private final ApplicationEventLogger eventLogger;
    private final JsonMapper jsonMapper;

    public WritableConfigurationService(ApplicationConfigurationRepository configurationRepository, ConfigurationValueValidator valueValidator, ConfigurationExpressionService expressionService, ConfigurationCacheInvalidator cacheInvalidator, ApplicationEventLogger eventLogger, JsonMapper jsonMapper) {
        this.configurationRepository = configurationRepository;
        this.valueValidator = valueValidator;
        this.expressionService = expressionService;
        this.cacheInvalidator = cacheInvalidator;
        this.eventLogger = eventLogger;
        this.jsonMapper = jsonMapper;
    }

    public void apply(long alertId, String alertName, UUID executionId, Iterable<WritableConfigurationValue> values) {
        for (WritableConfigurationValue value : values)
            applyOne(alertId, alertName, executionId, value);
    }

    private void applyOne(long alertId, String alertName, UUID executionId, WritableConfigurationValue result) {
        ApplicationConfiguration configuration = configurationRepository.findById(result.getConfigurationId()).orElse(null);
        if (configuration == null || !configuration.isWritable())
            return;

        JsonNode previousValue = configuration.getValue().deepCopy();
        try {
            if (result.getNullValue())
                throw new IllegalArgumentException("Writable configuration value must not be null");

            JsonNode value = valueValidator.validateAndNormalize(configuration.getValueType(), parse(configuration.getValueType(), result.getValue()));

            if (valuesEqual(configuration.getValue(), value))
                return;

            configuration.changeValue(configuration.getValueType(), value);
            try {
                expressionService.synchronizeDependencies(configuration);
            } catch (RuntimeException exception) {
                configuration.changeValue(configuration.getValueType(), previousValue);
                expressionService.synchronizeDependencies(configuration);
                throw exception;
            }
            configurationRepository.flush();
            cacheInvalidator.evictAfterCommit(configuration.getId(), Set.of(configuration.getName()));

            Map<String, Object> data = context(alertId, alertName, executionId, result, configuration);
            data.put("valueType", configuration.getValueType().name());
            eventLogger.successAfterCommit("CONFIGURATION_OVERWRITTEN_BY_ALERT", data);
        } catch (RuntimeException exception) {
            Map<String, Object> data = context(alertId, alertName, executionId, result, configuration);
            data.put("reason", exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage());
            eventLogger.errorAfterCommit("CONFIGURATION_OVERWRITE_REJECTED", data);
        }
    }

    private JsonNode parse(ConfigurationValueType type, String value) {
        if (type == ConfigurationValueType.STRING || type == ConfigurationValueType.EXPRESSION
                || type == ConfigurationValueType.DATE || type == ConfigurationValueType.TIME
                || type == ConfigurationValueType.DATE_TIME) {
            return StringNode.valueOf(value);
        }
        try {
            return jsonMapper.readTree(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Worker returned an invalid " + type + " value", exception);
        }
    }

    private static boolean valuesEqual(JsonNode first, JsonNode second) {
        if (first.isNumber() && second.isNumber())
            return first.decimalValue().compareTo(second.decimalValue()) == 0;

        return first.equals(second);
    }

    private static Map<String, Object> context(long alertId, String alertName, UUID executionId, WritableConfigurationValue result, ApplicationConfiguration configuration) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configurationId", configuration.getId());
        data.put("configurationName", configuration.getName());
        data.put("alertId", alertId);
        data.put("alertName", alertName);
        data.put("executionId", executionId);
        data.put("parameterName", result.getParameterName());
        return data;
    }
}
