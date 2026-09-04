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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.api.AlertCreateRequest;
import app.alertify.alerts.api.AlertDeletionImpactResponse;
import app.alertify.alerts.api.AlertParameterValueRequest;
import app.alertify.alerts.api.AlertResponse;
import app.alertify.alerts.api.AlertStateResponse;
import app.alertify.alerts.api.AlertUpdateRequest;
import app.alertify.alerts.execution.AlertExecutionOrchestrator;
import app.alertify.alerts.execution.AlertExecutionTrigger;
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
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.AlertSpecifications;
import app.alertify.logging.ApplicationEventLogger;

@Service
public class AlertManagementService {

    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "version", "name", "cronExpression", "enabled",
            "allowConcurrentExecutions", "createdAt", "updatedAt"
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
    private final TagRepository tagRepository;
    private final ApplicationEventLogger eventLogger;
    private final AlertScheduleService scheduleService;
    private final AlertExecutionOrchestrator executionOrchestrator;

    public AlertManagementService(AlertRepository alertRepository, AlertTemplateDefinitionRepository templateRepository, AlertTemplateParameterDefinitionRepository templateParameterRepository, AlertParameterValueRepository parameterValueRepository, AlertExecutionRepository executionRepository, AlertStateRepository stateRepository, ApplicationConfigurationRepository configurationRepository, ApplicationSecretRepository secretRepository, TagRepository tagRepository, ApplicationEventLogger eventLogger, AlertScheduleService scheduleService, AlertExecutionOrchestrator executionOrchestrator) {
        this.alertRepository = alertRepository;
        this.templateRepository = templateRepository;
        this.templateParameterRepository = templateParameterRepository;
        this.parameterValueRepository = parameterValueRepository;
        this.executionRepository = executionRepository;
        this.stateRepository = stateRepository;
        this.configurationRepository = configurationRepository;
        this.secretRepository = secretRepository;
        this.tagRepository = tagRepository;
        this.eventLogger = eventLogger;
        this.scheduleService = scheduleService;
        this.executionOrchestrator = executionOrchestrator;
    }

    @Transactional(readOnly = true)
    public Page<AlertResponse> search(String name, Long templateId, Set<Long> tagIds, boolean matchAllTags, Pageable pageable) {
        SearchValidation.validateSort(pageable, SORT_FIELDS);
        String normalizedName = name == null || name.isBlank() ? null : name.trim();
        Specification<Alert> specification = (_, _, cb) -> cb.conjunction();
        if (normalizedName != null)
            specification = specification.and(AlertSpecifications.nameContains(normalizedName));
        if (templateId != null)
            specification = specification.and(AlertSpecifications.hasTemplateId(templateId));
        if (!tagIds.isEmpty())
            specification = specification.and(matchAllTags
                    ? AlertSpecifications.hasAllTagIds(tagIds)
                    : AlertSpecifications.hasAnyTagId(tagIds));
        Page<Alert> alerts = alertRepository.findAll(specification, pageable);
        Page<AlertResponse> result = alerts.map(
                alert -> AlertMapper.toAlert(alert, parameterValueRepository.findAllByAlertIdOrdered(alert.getId()))
        );
        eventLogger.success("ALERT_PAGE_VIEWED", Map.of("page", result.getNumber(), "size", result.getSize(), "totalElements", result.getTotalElements()));
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
        Set<Tag> tags = resolveAlertTags(request.tagIds());
        Alert alert = alertRepository.saveAndFlush(new Alert(
                template, name, normalizeOptional(request.description()), cron, request.enabled(),
                request.allowConcurrentExecutions(), tags
        ));
        List<AlertParameterValue> values = synchronizeParameters(alert, request.parameters(), List.of());
        eventLogger.successAfterCommit("ALERT_CREATED", Map.of(
                "alertId", alert.getId(), "name", alert.getName(), "templateId", template.getId(),
                "allowConcurrentExecutions", alert.isConcurrentExecutionAllowed()
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
        alert.changeConcurrentExecution(request.allowConcurrentExecutions());
        alert.replaceTags(resolveAlertTags(request.tagIds()));

        List<AlertParameterValue> existing = parameterValueRepository.findAllByAlertIdOrdered(id);
        List<AlertParameterValue> values = synchronizeParameters(alert, request.parameters(), existing);
        alertRepository.flush();
        eventLogger.successAfterCommit("ALERT_UPDATED", Map.of(
                "alertId", alert.getId(), "name", alert.getName(), "version", alert.getVersion(),
                "allowConcurrentExecutions", alert.isConcurrentExecutionAllowed()
        ));
        scheduleService.rescheduleAfterCommit(alert.getId());
        return AlertMapper.toAlert(alert, values);
    }

    /**
     * Runs one alert immediately, outside its schedule. Disabled alerts are
     * allowed so an operator can test them before enabling.
     */
    @Transactional(readOnly = true)
    public void runNow(Long id) {
        Alert alert = alertRepository.findById(id).orElseThrow(() -> notFound("Alert", id));
        boolean accepted = executionOrchestrator.trigger(
                alert.getId(), alert.getName(), alert.isConcurrentExecutionAllowed(),
                AlertExecutionTrigger.MANUAL, eventLogger.currentUsername()
        );
        if (!accepted) {
            throw new ConflictException(
                    "ALERT_ALREADY_RUNNING",
                    "Alert '" + alert.getName() + "' is already running and does not allow concurrent executions",
                    Map.of("alertName", alert.getName())
            );
        }
    }

    @Transactional
    public void delete(Long id, long version) {
        Alert alert = alertRepository.findById(id).orElseThrow(() -> notFound("Alert", id));
        ensureVersion(alert, version);
        if (executionOrchestrator.isRunning(id))
            throw new ConflictException("Alert is currently running and cannot be deleted");

        // Executions first: their foreign key to the alert is ON DELETE RESTRICT.
        // The application log keeps the trail, so only the history rows are lost.
        int executionsDeleted = executionRepository.deleteByAlertId(id);
        executionRepository.flush();
        List<AlertParameterValue> values = parameterValueRepository.findAllByAlertIdOrdered(id);
        parameterValueRepository.deleteAll(values);
        parameterValueRepository.flush();
        alertRepository.delete(alert);
        eventLogger.successAfterCommit("ALERT_DELETED", Map.of(
                "alertId", id, "name", alert.getName(), "executionsDeleted", executionsDeleted
        ));
        scheduleService.removeAfterCommit(id);
    }

    /**
     * What deleting one alert would destroy, so the caller can confirm knowing
     * how much execution history is at stake.
     */
    @Transactional(readOnly = true)
    public AlertDeletionImpactResponse deletionImpact(Long id) {
        Alert alert = alertRepository.findById(id).orElseThrow(() -> notFound("Alert", id));
        return new AlertDeletionImpactResponse(
                alert.getId(), alert.getName(), executionRepository.countByAlert_Id(id)
        );
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

    private Set<Tag> resolveAlertTags(Set<Long> requestedIds) {
        if (requestedIds.isEmpty())
            return Set.of();

        List<Tag> tags = tagRepository.findAllByIdInAndScope(requestedIds, TagScope.ALERT);
        if (tags.size() != requestedIds.size())
            throw new ResourceNotFoundException("One or more alert tags were not found");

        return new java.util.LinkedHashSet<>(tags);
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
