package app.alertify.alerts.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.api.AlertCreateRequest;
import app.alertify.alerts.api.AlertParameterValueRequest;
import app.alertify.alerts.api.AlertResponse;
import app.alertify.alerts.api.AlertStateResponse;
import app.alertify.alerts.api.AlertUpdateRequest;
import app.alertify.alerts.execution.AlertExecutionOrchestrator;
import app.alertify.alerts.execution.AlertScheduleService;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertParameterValue;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidAlertRequestException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.service.SearchValidation;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.logging.ApplicationEventLogger;

@Service
public class AlertManagementService {

    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "version", "name", "cronExpression", "enabled", "createdAt", "updatedAt"
    );
    private static final String HIDDEN_CONFIGURATION = "KEY_PART";

    private final AlertRepository alertRepository;
    private final AlertTemplateDefinitionRepository templateRepository;
    private final AlertTemplateParameterDefinitionRepository templateParameterRepository;
    private final AlertParameterValueRepository parameterValueRepository;
    private final AlertExecutionRepository executionRepository;
    private final AlertStateRepository stateRepository;
    private final ApplicationConfigurationRepository configurationRepository;
    private final ApplicationSecretRepository secretRepository;
    private final ApplicationEventLogger eventLogger;
    private final AlertScheduleService scheduleService;
    private final AlertExecutionOrchestrator executionOrchestrator;

    public AlertManagementService(AlertRepository alertRepository, AlertTemplateDefinitionRepository templateRepository, AlertTemplateParameterDefinitionRepository templateParameterRepository, AlertParameterValueRepository parameterValueRepository, AlertExecutionRepository executionRepository, AlertStateRepository stateRepository, ApplicationConfigurationRepository configurationRepository, ApplicationSecretRepository secretRepository, ApplicationEventLogger eventLogger, AlertScheduleService scheduleService, AlertExecutionOrchestrator executionOrchestrator) {
        this.alertRepository = alertRepository;
        this.templateRepository = templateRepository;
        this.templateParameterRepository = templateParameterRepository;
        this.parameterValueRepository = parameterValueRepository;
        this.executionRepository = executionRepository;
        this.stateRepository = stateRepository;
        this.configurationRepository = configurationRepository;
        this.secretRepository = secretRepository;
        this.eventLogger = eventLogger;
        this.scheduleService = scheduleService;
        this.executionOrchestrator = executionOrchestrator;
    }

    @Transactional(readOnly = true)
    public Page<AlertResponse> search(String name, Long templateId, Pageable pageable) {
        SearchValidation.validateSort(pageable, SORT_FIELDS);
        String normalizedName = name == null || name.isBlank() ? null : name.trim();
        Page<Alert> alerts;
        if (templateId == null && normalizedName == null)
            alerts = alertRepository.findAll(pageable);
        else if (templateId == null)
            alerts = alertRepository.findAllByNameContainingIgnoreCase(normalizedName, pageable);
        else if (normalizedName == null)
            alerts = alertRepository.findAllByTemplate_Id(templateId, pageable);
        else
            alerts = alertRepository.findAllByTemplate_IdAndNameContainingIgnoreCase(
                    templateId, normalizedName, pageable
            );
        Page<AlertResponse> result = alerts.map(
                alert -> AlertMapper.toAlert(alert, parameterValueRepository.findAllByAlertIdOrdered(alert.getId()))
        );
        eventLogger.success("ALERT_PAGE_VIEWED", Map.of(
                "page", result.getNumber(), "size", result.getSize(), "totalElements", result.getTotalElements()
        ));
        return result;
    }

    @Transactional(readOnly = true)
    public AlertStateResponse state(Long alertId) {
        if (!alertRepository.existsById(alertId))
            throw notFound("Alert", alertId);

        String state = stateRepository.findById(alertId).map(value -> value.getState()).orElse("");
        return new AlertStateResponse(alertId, state);
    }

    @Transactional
    public AlertResponse create(AlertCreateRequest request) {
        String name = normalizeRequired(request.name(), "name");
        ensureNameAvailable(name, null);
        String cron = validateCron(request.cronExpression());
        AlertTemplateDefinition template = templateRepository.findById(request.templateId())
                .orElseThrow(() -> notFound("Alert template", request.templateId()));
        Alert alert = alertRepository.saveAndFlush(new Alert(
                template, name, normalizeOptional(request.description()), cron, request.enabled()
        ));
        List<AlertParameterValue> values = synchronizeParameters(alert, request.parameters(), List.of());
        eventLogger.successAfterCommit("ALERT_CREATED", Map.of(
                "alertId", alert.getId(), "name", alert.getName(), "templateId", template.getId()
        ));
        scheduleService.rescheduleAfterCommit(alert.getId());
        return AlertMapper.toAlert(alert, values);
    }

    @Transactional
    public AlertResponse update(Long id, AlertUpdateRequest request) {
        Alert alert = alertRepository.findById(id).orElseThrow(() -> notFound("Alert", id));
        ensureVersion(alert, request.version());
        String name = normalizeRequired(request.name(), "name");
        ensureNameAvailable(name, id);
        alert.rename(name);
        alert.changeDescription(normalizeOptional(request.description()));
        alert.reschedule(validateCron(request.cronExpression()));
        if (request.enabled())
            alert.enable();
        else
            alert.disable();

        List<AlertParameterValue> existing = parameterValueRepository.findAllByAlertIdOrdered(id);
        List<AlertParameterValue> values = synchronizeParameters(alert, request.parameters(), existing);
        alertRepository.flush();
        eventLogger.successAfterCommit("ALERT_UPDATED", Map.of(
                "alertId", alert.getId(), "name", alert.getName(), "version", alert.getVersion()
        ));
        scheduleService.rescheduleAfterCommit(alert.getId());
        return AlertMapper.toAlert(alert, values);
    }

    @Transactional
    public void delete(Long id, long version) {
        Alert alert = alertRepository.findById(id).orElseThrow(() -> notFound("Alert", id));
        ensureVersion(alert, version);
        if (executionOrchestrator.isRunning(id))
            throw new ConflictException("Alert is currently running and cannot be deleted");

        if (executionRepository.existsByAlert_Id(id))
            throw new ConflictException("Alert has execution history and cannot be deleted");
        
        List<AlertParameterValue> values = parameterValueRepository.findAllByAlertIdOrdered(id);
        parameterValueRepository.deleteAll(values);
        parameterValueRepository.flush();
        alertRepository.delete(alert);
        eventLogger.successAfterCommit("ALERT_DELETED", Map.of("alertId", id, "name", alert.getName()));
        scheduleService.removeAfterCommit(id);
    }

    private List<AlertParameterValue> synchronizeParameters(Alert alert, List<AlertParameterValueRequest> requested, List<AlertParameterValue> existing) {
        List<AlertTemplateParameterDefinition> definitions = templateParameterRepository
                .findAllByTemplate_IdOrderByParameterOrderAscIdAsc(alert.getTemplate().getId());
        Map<String, AlertTemplateParameterDefinition> definitionsByKey = definitions.stream()
                .collect(Collectors.toMap(AlertTemplateParameterDefinition::getParameterKey, Function.identity()));
        Map<String, AlertParameterValueRequest> requestsByKey = new LinkedHashMap<>();
        for (AlertParameterValueRequest value : requested) {
            if (!definitionsByKey.containsKey(value.parameterKey()))
                throw invalid("Unknown parameter '" + value.parameterKey() + "' for the selected template");

            if (requestsByKey.put(value.parameterKey(), value) != null)
                throw invalid("Parameter '" + value.parameterKey() + "' was provided more than once");
        }

        Map<String, AlertParameterValue> existingByKey = existing.stream().collect(Collectors.toMap(
                value -> value.getTemplateParameter().getParameterKey(), Function.identity()
        ));
        List<AlertParameterValue> result = new ArrayList<>();
        Set<Long> retainedIds = new HashSet<>();
        for (AlertTemplateParameterDefinition definition : definitions) {
            AlertParameterValueRequest value = requestsByKey.get(definition.getParameterKey());
            if (value == null && definition.getDefaultValue() != null) {
                value = new AlertParameterValueRequest(
                        definition.getParameterKey(), AlertParameterSource.TEXT,
                        definition.getDefaultValue(), null, null
                );
            }
            if (value == null) {
                if (definition.isRequired())
                    throw invalid("Required parameter '" + definition.getParameterKey() + "' has no value");

                continue;
            }

            AlertParameterValue parameterValue = existingByKey.get(definition.getParameterKey());
            if (parameterValue == null)
                parameterValue = createValue(alert, definition, value);
            else
                replaceValue(parameterValue, definition, value);

            result.add(parameterValueRepository.save(parameterValue));
            if (parameterValue.getId() != null)
                retainedIds.add(parameterValue.getId());
        }

        List<AlertParameterValue> removed = existing.stream()
                .filter(value -> value.getId() != null && !retainedIds.contains(value.getId()))
                .toList();
        if (!removed.isEmpty())
            parameterValueRepository.deleteAll(removed);

        parameterValueRepository.flush();
        return result;
    }

    private AlertParameterValue createValue(Alert alert, AlertTemplateParameterDefinition definition, AlertParameterValueRequest request) {
        try {
            return switch (request.source()) {
                case TEXT -> AlertParameterValue.text(alert, definition, validateText(definition, request.textValue()));
                case CONFIGURATION -> AlertParameterValue.configuration(alert, definition, configuration(request.configurationId()));
                case SECRET -> AlertParameterValue.secret(alert, definition, secret(request.secretId()));
            };
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage(), exception);
        }
    }

    private void replaceValue(AlertParameterValue target, AlertTemplateParameterDefinition definition, AlertParameterValueRequest request) {
        try {
            switch (request.source()) {
                case TEXT -> target.replaceWithText(validateText(definition, request.textValue()));
                case CONFIGURATION -> target.replaceWithConfiguration(configuration(request.configurationId()));
                case SECRET -> target.replaceWithSecret(secret(request.secretId()));
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage(), exception);
        }
    }

    private ApplicationConfiguration configuration(Long id) {
        ApplicationConfiguration configuration = configurationRepository.findById(id)
                .orElseThrow(() -> notFound("Configuration", id));
        if (HIDDEN_CONFIGURATION.equalsIgnoreCase(configuration.getName()))
            throw invalid("Configuration 'KEY_PART' cannot be used as an alert binding");

        return configuration;
    }

    private ApplicationSecret secret(Long id) {
        return secretRepository.findById(id).orElseThrow(() -> notFound("Secret", id));
    }

    private String validateText(AlertTemplateParameterDefinition definition, String value) {
        if (value == null)
            throw invalid("Text value is required for parameter '" + definition.getParameterKey() + "'");

        if (!definition.isBindingAllowed() && !definition.getOptions().contains(value))
            throw invalid("Parameter '" + definition.getParameterKey() + "' must use one of its declared options");
        
        try {
            validateJavaType(definition.getJavaType(), value);
        } catch (RuntimeException exception) {
            throw invalid("Parameter '" + definition.getParameterKey() + "' is not a valid " + definition.getJavaType(), exception);
        }
        return value;
    }

    private static void validateJavaType(String javaType, String value) {
        switch (javaType) {
            case "byte", "java.lang.Byte" -> Byte.parseByte(value);
            case "short", "java.lang.Short" -> Short.parseShort(value);
            case "int", "java.lang.Integer" -> Integer.parseInt(value);
            case "long", "java.lang.Long" -> Long.parseLong(value);
            case "float", "java.lang.Float" -> Float.parseFloat(value);
            case "double", "java.lang.Double" -> Double.parseDouble(value);
            case "boolean", "java.lang.Boolean" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
                    throw new IllegalArgumentException("not a boolean");
            }
            case "char", "java.lang.Character" -> {
                if (value.length() != 1)
                    throw new IllegalArgumentException("not a character");
            }
            case "java.math.BigInteger" -> new BigInteger(value);
            case "java.math.BigDecimal" -> new BigDecimal(value);
            case "java.net.URI" -> URI.create(value);
            case "java.time.Duration" -> Duration.parse(value);
            case "java.time.Instant" -> Instant.parse(value);
            default -> { }
        }
    }

    private void ensureNameAvailable(String name, Long id) {
        boolean exists = id == null
                ? alertRepository.existsByNameIgnoreCase(name)
                : alertRepository.existsByNameIgnoreCaseAndIdNot(name, id);
        if (exists)
            throw new ConflictException("An alert named '" + name + "' already exists");
    }

    private static String validateCron(String value) {
        String cron = normalizeRequired(value, "cronExpression");
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid cron expression: " + exception.getMessage(), exception);
        }
        return cron;
    }

    private static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank())
            throw invalid(field + " must not be blank");

        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void ensureVersion(Alert alert, long requestedVersion) {
        if (alert.getVersion() != requestedVersion)
            throw new ConflictException("Alert was modified by another request; reload it and try again");
    }

    private static ResourceNotFoundException notFound(String resource, Long id) {
        return new ResourceNotFoundException(resource + " " + id + " was not found");
    }

    private static InvalidAlertRequestException invalid(String message) {
        return new InvalidAlertRequestException(message);
    }

    private static InvalidAlertRequestException invalid(String message, Throwable cause) {
        return new InvalidAlertRequestException(message, cause);
    }
}
