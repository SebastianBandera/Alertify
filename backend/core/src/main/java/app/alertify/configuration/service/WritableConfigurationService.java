package app.alertify.configuration.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.ConfigurationBinaryValue;
import app.alertify.jpa.repository.ConfigurationBinaryValueRepository;
import app.alertify.binary.BinaryPayloadService;
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
    private final ConfigurationBinaryValueRepository binaryRepository;
    private final BinaryPayloadService binaryPayloadService;

    public WritableConfigurationService(ApplicationConfigurationRepository configurationRepository, ConfigurationValueValidator valueValidator, ConfigurationExpressionService expressionService, ConfigurationCacheInvalidator cacheInvalidator, ApplicationEventLogger eventLogger, JsonMapper jsonMapper, ConfigurationBinaryValueRepository binaryRepository, BinaryPayloadService binaryPayloadService) {
        this.configurationRepository = configurationRepository;
        this.valueValidator = valueValidator;
        this.expressionService = expressionService;
        this.cacheInvalidator = cacheInvalidator;
        this.eventLogger = eventLogger;
        this.jsonMapper = jsonMapper;
        this.binaryRepository = binaryRepository;
        this.binaryPayloadService = binaryPayloadService;
    }

    public void apply(long alertId, String alertName, UUID executionId, Iterable<WritableConfigurationValue> values) {
        for (WritableConfigurationValue value : values)
            applyOne(new Owner("alert", alertId, alertName, "CONFIGURATION_OVERWRITTEN_BY_ALERT"), executionId, value);
    }

    public void applyProcedure(long procedureId, String procedureName, UUID executionId, Iterable<WritableConfigurationValue> values) {
        for (WritableConfigurationValue value : values)
            applyOne(new Owner("procedure", procedureId, procedureName,
                    "CONFIGURATION_OVERWRITTEN_BY_PROCEDURE"), executionId, value);
    }

    private void applyOne(Owner owner, UUID executionId, WritableConfigurationValue result) {
        ApplicationConfiguration configuration = configurationRepository.findByIdForUpdate(result.getConfigurationId()).orElse(null);
        if (configuration == null || !configuration.isWritable())
            return;

        JsonNode previousValue = configuration.getValue().deepCopy();
        try {
            if (result.hasExpectedVersion() && configuration.getVersion() != result.getExpectedVersion())
                throw new IllegalStateException("Configuration changed after execution preparation");

            if (result.getNullValue())
                throw new IllegalArgumentException("Writable configuration value must not be null");

            if (configuration.getValueType() == ConfigurationValueType.BINARY) {
                byte[] raw = binaryPayloadService.decompress(result.getBinaryValue().toByteArray());
                var prepared = binaryPayloadService.prepare(raw, configuration.getBinaryFileName(), configuration.getBinaryContentType());
                binaryRepository.save(new ConfigurationBinaryValue(configuration.getId(), prepared.zip()));
                configuration.changeBinaryMetadata(prepared.fileName(), prepared.contentType(), prepared.size(), prepared.zipSize(), prepared.sha256());
                configurationRepository.flush();
                cacheInvalidator.evictAfterCommit(configuration.getId(), Set.of(configuration.getName()));
                eventLogger.successAfterCommit(owner.successEvent(), context(owner, executionId, result, configuration));
                return;
            }

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

            Map<String, Object> data = context(owner, executionId, result, configuration);
            data.put("valueType", configuration.getValueType().name());
            eventLogger.successAfterCommit(owner.successEvent(), data);
        } catch (RuntimeException exception) {
            Map<String, Object> data = context(owner, executionId, result, configuration);
            data.put("reason", exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage());
            eventLogger.errorAfterCommit("CONFIGURATION_OVERWRITE_REJECTED", data);
        }
    }

    private JsonNode parse(ConfigurationValueType type, String value) {
        if (type == ConfigurationValueType.BINARY) throw new IllegalArgumentException("BINARY requires binary transport");
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

    private static Map<String, Object> context(Owner owner, UUID executionId, WritableConfigurationValue result, ApplicationConfiguration configuration) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configurationId", configuration.getId());
        data.put("configurationName", configuration.getName());
        data.put(owner.type() + "Id", owner.id());
        data.put(owner.type() + "Name", owner.name());
        data.put("executionId", executionId);
        data.put("parameterName", result.getParameterName());
        return data;
    }

    private record Owner(String type, long id, String name, String successEvent) { }
}
