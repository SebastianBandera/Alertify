package app.alertify.procedures.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidProcedureRequestException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.service.SearchValidation;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.ProcedureExecutionRepository;
import app.alertify.jpa.repository.ProcedureParameterValueRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.ProcedureSpecifications;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.api.ProcedureCreateRequest;
import app.alertify.procedures.api.ProcedureDeletionImpactResponse;
import app.alertify.procedures.api.ProcedureParameterValueRequest;
import app.alertify.procedures.api.ProcedureResponse;
import app.alertify.procedures.api.ProcedureUpdateRequest;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator;
import app.alertify.procedures.execution.ProcedureExecutionStatus;
import app.alertify.procedures.execution.ProcedureScheduleService;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureParameterValue;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;

/**
 * CRUD and manual runs for user-configured procedures. Updates and deletions
 * are guarded by the optimistic version sent by the client, and parameter
 * values are always synchronized against the template definition, so an unknown
 * or duplicated parameter is rejected and a missing one falls back to its
 * default.
 *
 * <p>A procedure may only be deleted when nothing references it: no alert or
 * procedure parameter is bound to it and no execution is in progress.
 */
@Service
public class ProcedureManagementService {

    private static final Set<String> SORT_FIELDS = Set.of("id", "version", "name", "cronExpression", "enabled", "allowConcurrentExecutions", "createdAt", "updatedAt");

    private final ProcedureRepository procedureRepository;
    private final ProcedureTemplateDefinitionRepository templateRepository;
    private final ProcedureTemplateParameterDefinitionRepository templateParameterRepository;
    private final ProcedureParameterValueRepository parameterValueRepository;
    private final ProcedureExecutionRepository executionRepository;
    private final AlertParameterValueRepository alertParameterValueRepository;
    private final ApplicationConfigurationRepository configurationRepository;
    private final ApplicationSecretRepository secretRepository;
    private final TagRepository tagRepository;
    private final ApplicationEventLogger eventLogger;
    private final ProcedureExecutionOrchestrator orchestrator;
    private final ProcedureScheduleService scheduleService;

    public ProcedureManagementService(ProcedureRepository procedureRepository,
            ProcedureTemplateDefinitionRepository templateRepository,
            ProcedureTemplateParameterDefinitionRepository templateParameterRepository,
            ProcedureParameterValueRepository parameterValueRepository,
            ProcedureExecutionRepository executionRepository,
            AlertParameterValueRepository alertParameterValueRepository,
            ApplicationConfigurationRepository configurationRepository,
            ApplicationSecretRepository secretRepository, TagRepository tagRepository,
            ApplicationEventLogger eventLogger, ProcedureExecutionOrchestrator orchestrator,
            ProcedureScheduleService scheduleService) {
        this.procedureRepository = procedureRepository;
        this.templateRepository = templateRepository;
        this.templateParameterRepository = templateParameterRepository;
        this.parameterValueRepository = parameterValueRepository;
        this.executionRepository = executionRepository;
        this.alertParameterValueRepository = alertParameterValueRepository;
        this.configurationRepository = configurationRepository;
        this.secretRepository = secretRepository;
        this.tagRepository = tagRepository;
        this.eventLogger = eventLogger;
        this.orchestrator = orchestrator;
        this.scheduleService = scheduleService;
    }

    @Transactional(readOnly = true)
    public Page<ProcedureResponse> search(String name, Long templateId, Set<Long> tagIds, boolean matchAllTags, Pageable pageable) {
        SearchValidation.validateSort(pageable, SORT_FIELDS);
        Specification<Procedure> specification = (_, _, cb) -> cb.conjunction();
        if (name != null && !name.isBlank())
            specification = specification.and(ProcedureSpecifications.nameContains(name.trim()));

        if (templateId != null)
            specification = specification.and(ProcedureSpecifications.hasTemplateId(templateId));

        if (!tagIds.isEmpty())
            specification = specification.and(matchAllTags
                    ? ProcedureSpecifications.hasAllTagIds(tagIds)
                    : ProcedureSpecifications.hasAnyTagId(tagIds));

        Page<ProcedureResponse> result = procedureRepository.findAll(specification, pageable)
                .map(value -> ProcedureMapper.toProcedure(value,
                        parameterValueRepository.findAllByOwnerIdOrdered(value.getId())));
        eventLogger.success("PROCEDURE_PAGE_VIEWED", Map.of("page", result.getNumber(), "size", result.getSize(), "totalElements", result.getTotalElements()));
        return result;
    }

    @Transactional
    public ProcedureResponse create(ProcedureCreateRequest request) {
        String name = required(request.name(), "name");
        ensureNameAvailable(name, null);
        ProcedureTemplateDefinition template = templateRepository.findById(request.templateId())
                .orElseThrow(() -> notFound("Procedure template", request.templateId()));
        Procedure procedure = procedureRepository.saveAndFlush(new Procedure(template, name,
                optional(request.description()), validateCron(request.cronExpression()), request.enabled(), request.allowConcurrentExecutions() == null || request.allowConcurrentExecutions(),
                resolveTags(request.tagIds())));
        List<ProcedureParameterValue> values = synchronizeParameters(procedure, request.parameters(), List.of());
        eventLogger.successAfterCommit("PROCEDURE_CREATED", Map.of("procedureId", procedure.getId(), "name", procedure.getName(), "templateId", template.getId(), "allowConcurrentExecutions", procedure.isConcurrentExecutionAllowed()));
        scheduleService.rescheduleAfterCommit(procedure.getId());
        return ProcedureMapper.toProcedure(procedure, values);
    }

    @Transactional
    public ProcedureResponse update(Long id, ProcedureUpdateRequest request) {
        Procedure procedure = procedureRepository.findById(id).orElseThrow(() -> notFound("Procedure", id));
        if (procedure.getVersion() != request.version())
            throw new ConflictException("Procedure was modified by another request; reload it and try again");

        String name = required(request.name(), "name");
        ensureNameAvailable(name, id);
        procedure.rename(name);
        procedure.changeDescription(optional(request.description()));
        procedure.reschedule(validateCron(request.cronExpression()));
        if (request.enabled())
            procedure.enable();
        else
            procedure.disable();

        procedure.changeConcurrentExecution(request.allowConcurrentExecutions());
        procedure.replaceTags(resolveTags(request.tagIds()));
        List<ProcedureParameterValue> values = synchronizeParameters(procedure, request.parameters(),
                parameterValueRepository.findAllByOwnerIdOrdered(id));
        procedureRepository.flush();
        eventLogger.successAfterCommit("PROCEDURE_UPDATED", Map.of("procedureId", id, "name", procedure.getName(), "version", procedure.getVersion(), "allowConcurrentExecutions", procedure.isConcurrentExecutionAllowed()));
        scheduleService.rescheduleAfterCommit(procedure.getId());
        return ProcedureMapper.toProcedure(procedure, values);
    }

    @Transactional(readOnly = true)
    public void runNow(Long id) {
        Procedure procedure = procedureRepository.findById(id).orElseThrow(() -> notFound("Procedure", id));
        boolean accepted = orchestrator.triggerManual(procedure.getId(), procedure.getName(), procedure.isConcurrentExecutionAllowed(), eventLogger.currentUsername());
        if (!accepted)
            throw new ConflictException("PROCEDURE_ALREADY_RUNNING",
                    "Procedure '" + procedure.getName() + "' is already running and does not allow concurrent executions",
                    Map.of("procedureName", procedure.getName()));
    }

    @Transactional(readOnly = true)
    public ProcedureDeletionImpactResponse deletionImpact(Long id) {
        Procedure procedure = procedureRepository.findById(id).orElseThrow(() -> notFound("Procedure", id));
        return new ProcedureDeletionImpactResponse(id, procedure.getName(), executionRepository.countByProcedure_Id(id),
                alertParameterValueRepository.countByProcedure_Id(id), parameterValueRepository.countByReferencedProcedure_Id(id));
    }

    @Transactional
    public void delete(Long id, long version) {
        Procedure procedure = procedureRepository.findById(id).orElseThrow(() -> notFound("Procedure", id));
        if (procedure.getVersion() != version)
            throw new ConflictException("Procedure was modified by another request; reload it and try again");

        if (orchestrator.isRunning(id) || executionRepository.existsByProcedure_IdAndStatus(id, ProcedureExecutionStatus.RUNNING))
            throw new ConflictException("Procedure is currently running and cannot be deleted");

        long alertReferences = alertParameterValueRepository.countByProcedure_Id(id);
        long procedureReferences = parameterValueRepository.countByReferencedProcedure_Id(id);
        if (alertReferences + procedureReferences > 0)
            throw new ConflictException("PROCEDURE_IN_USE", "Procedure '" + procedure.getName() + "' is referenced",
                    Map.of("procedureName", procedure.getName()));

        long executions = executionRepository.countByProcedure_Id(id);
        executionRepository.deleteAllByProcedure_Id(id);
        executionRepository.flush();
        parameterValueRepository.deleteAllByOwner_Id(id);
        parameterValueRepository.flush();
        procedureRepository.delete(procedure);
        eventLogger.successAfterCommit("PROCEDURE_DELETED", Map.of("procedureId", id, "name", procedure.getName(), "executionsDeleted", executions));
        scheduleService.removeAfterCommit(id);
    }

    private List<ProcedureParameterValue> synchronizeParameters(Procedure procedure, List<ProcedureParameterValueRequest> requested, List<ProcedureParameterValue> existing) {
        List<ProcedureTemplateParameterDefinition> definitions = templateParameterRepository
                .findAllByTemplate_IdOrderByParameterOrderAscIdAsc(procedure.getTemplate().getId());
        Map<String, ProcedureTemplateParameterDefinition> definitionsByKey = definitions.stream()
                .collect(Collectors.toMap(ProcedureTemplateParameterDefinition::getParameterKey, Function.identity()));
        Map<String, ProcedureParameterValueRequest> requests = new LinkedHashMap<>();
        for (ProcedureParameterValueRequest value : requested) {
            if (!definitionsByKey.containsKey(value.parameterKey()))
                throw invalid("Unknown parameter '" + value.parameterKey() + "' for the selected template");

            if (requests.put(value.parameterKey(), value) != null)
                throw invalid("Parameter '" + value.parameterKey() + "' was provided more than once");
        }
        Map<String, ProcedureParameterValue> existingByKey = existing.stream().collect(Collectors.toMap(
                value -> value.getTemplateParameter().getParameterKey(), Function.identity()));
        List<ProcedureParameterValue> result = new ArrayList<>();
        Set<Long> retained = new HashSet<>();
        for (ProcedureTemplateParameterDefinition definition : definitions) {
            ProcedureParameterValueRequest request = requests.get(definition.getParameterKey());
            if (request == null && definition.getDefaultValue() != null)
                request = new ProcedureParameterValueRequest(definition.getParameterKey(), AlertParameterSource.TEXT,
                        definition.getDefaultValue(), null, null, null);

            if (request == null) {
                if (definition.isRequired())
                    throw invalid("Required parameter '" + definition.getParameterKey() + "' has no value");

                continue;
            }
            if (!definition.getAllowedSources().contains(request.source()))
                throw invalid("Source " + request.source() + " is not allowed for parameter '" + definition.getParameterKey() + "'");

            ProcedureParameterValue value = existingByKey.get(definition.getParameterKey());
            if (value == null)
                value = createValue(procedure, definition, request);
            else
                replaceValue(value, definition, request);

            result.add(parameterValueRepository.save(value));
            if (value.getId() != null)
                retained.add(value.getId());
        }
        List<ProcedureParameterValue> removed = existing.stream()
                .filter(value -> value.getId() != null && !retained.contains(value.getId())).toList();
        if (!removed.isEmpty())
            parameterValueRepository.deleteAll(removed);

        parameterValueRepository.flush();
        return result;
    }

    private ProcedureParameterValue createValue(Procedure owner, ProcedureTemplateParameterDefinition definition, ProcedureParameterValueRequest request) {
        try {
            return switch (request.source()) {
                case TEXT -> ProcedureParameterValue.text(owner, definition, validateText(definition, request.textValue()));
                case CONFIGURATION -> ProcedureParameterValue.configuration(owner, definition, configuration(request.configurationId()));
                case SECRET -> ProcedureParameterValue.secret(owner, definition, secret(request.secretId()));
                case PROCEDURE -> ProcedureParameterValue.procedure(owner, definition, procedure(request.procedureId()));
            };
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage(), exception);
        }
    }

    private void replaceValue(ProcedureParameterValue target, ProcedureTemplateParameterDefinition definition, ProcedureParameterValueRequest request) {
        try {
            switch (request.source()) {
                case TEXT -> target.replaceWithText(validateText(definition, request.textValue()));
                case CONFIGURATION -> target.replaceWithConfiguration(configuration(request.configurationId()));
                case SECRET -> target.replaceWithSecret(secret(request.secretId()));
                case PROCEDURE -> target.replaceWithProcedure(procedure(request.procedureId()));
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage(), exception);
        }
    }

    private ApplicationConfiguration configuration(Long id) {
        return configurationRepository.findById(id).orElseThrow(() -> notFound("Configuration", id));
    }

    private ApplicationSecret secret(Long id) {
        return secretRepository.findById(id).orElseThrow(() -> notFound("Secret", id));
    }

    private Procedure procedure(Long id) {
        return procedureRepository.findById(id).orElseThrow(() -> notFound("Procedure", id));
    }

    private Set<Tag> resolveTags(Set<Long> ids) {
        if (ids.isEmpty())
            return Set.of();

        List<Tag> tags = tagRepository.findAllByIdInAndScope(ids, TagScope.PROCEDURE);
        if (tags.size() != ids.size())
            throw notFound("One or more procedure tags", null);

        return new java.util.LinkedHashSet<>(tags);
    }

    private String validateText(ProcedureTemplateParameterDefinition definition, String value) {
        if (value == null)
            throw invalid("Text value is required for parameter '" + definition.getParameterKey() + "'");

        if (app.alertify.procedures.Procedure.class.getName().equals(definition.getJavaType()))
            throw invalid("Procedure parameter '" + definition.getParameterKey() + "' must use a procedure binding");

        if (!definition.isBindingAllowed() && !definition.getOptions().contains(value))
            throw invalid("Parameter '" + definition.getParameterKey() + "' must use one of its declared options");

        try {
            validateJavaType(definition.getJavaType(), value);
        } catch (RuntimeException exception) {
            throw invalid("Parameter '" + definition.getParameterKey() + "' is not a valid " + definition.getJavaType(), exception);
        }
        return value;
    }

    private static void validateJavaType(String type, String value) {
        switch (type) {
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
        boolean exists = id == null ? procedureRepository.findByNameIgnoreCase(name).isPresent()
                : procedureRepository.existsByNameIgnoreCaseAndIdNot(name, id);
        if (exists)
            throw new ConflictException("A procedure named '" + name + "' already exists");
    }

    static String validateCron(String value) {
        String cron = required(value, "cronExpression");
        if (Scheduled.CRON_DISABLED.equals(cron))
            return cron;

        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid cron expression: " + exception.getMessage(), exception);
        }
        // A syntactically valid expression such as "0 0 5 31 2 ?" may never fire; the scheduler
        // cannot register it, so reject it here instead of failing after commit or at startup.
        if (expression.next(LocalDateTime.now()) == null)
            throw invalid("Cron expression '" + cron + "' never matches a future date");

        return cron;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank())
            throw invalid(name + " must not be blank");

        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResourceNotFoundException notFound(String resource, Long id) {
        return new ResourceNotFoundException(id == null ? resource + " were not found" : resource + " " + id + " was not found");
    }

    private static InvalidProcedureRequestException invalid(String message) {
        return new InvalidProcedureRequestException(message);
    }

    private static InvalidProcedureRequestException invalid(String message, Throwable cause) {
        return new InvalidProcedureRequestException(message, cause);
    }
}
