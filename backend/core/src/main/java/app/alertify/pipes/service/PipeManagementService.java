package app.alertify.pipes.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.model.Alert;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidPipeRequestException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.HookRepository;
import app.alertify.jpa.repository.PipeExecutionRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.ProcedureParameterValueRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateOutputDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.pipes.api.PipeCreateRequest;
import app.alertify.pipes.api.PipeDeletionImpactResponse;
import app.alertify.pipes.api.PipeOptionResponse;
import app.alertify.pipes.api.PipeOptionsResponse;
import app.alertify.pipes.api.PipeResponse;
import app.alertify.pipes.api.PipeStepRequest;
import app.alertify.pipes.api.PipeUpdateRequest;
import app.alertify.pipes.execution.PipeExecutionOrchestrator;
import app.alertify.pipes.model.Pipe;
import app.alertify.pipes.model.PipeStep;
import app.alertify.pipes.model.PipeStepBinding;
import app.alertify.pipes.model.PipeStepType;
import app.alertify.procedures.artifact.ProcedureArtifactInput;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureTemplateOutputDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;

@Service
public class PipeManagementService {
    private final PipeRepository pipeRepository;
    private final PipeExecutionRepository executionRepository;
    private final AlertRepository alertRepository;
    private final ProcedureRepository procedureRepository;
    private final ProcedureTemplateParameterDefinitionRepository parameterRepository;
    private final ProcedureTemplateOutputDefinitionRepository outputRepository;
    private final HookRepository hookRepository;
    private final ProcedureParameterValueRepository procedureParameterRepository;
    private final TagRepository tagRepository;
    private final PipeExecutionOrchestrator orchestrator;
    private final ApplicationEventLogger eventLogger;

    public PipeManagementService(PipeRepository pipeRepository, PipeExecutionRepository executionRepository, AlertRepository alertRepository, ProcedureRepository procedureRepository, ProcedureTemplateParameterDefinitionRepository parameterRepository, ProcedureTemplateOutputDefinitionRepository outputRepository, HookRepository hookRepository, ProcedureParameterValueRepository procedureParameterRepository, TagRepository tagRepository, PipeExecutionOrchestrator orchestrator, ApplicationEventLogger eventLogger) {
        this.pipeRepository = pipeRepository;
        this.executionRepository = executionRepository;
        this.alertRepository = alertRepository;
        this.procedureRepository = procedureRepository;
        this.parameterRepository = parameterRepository;
        this.outputRepository = outputRepository;
        this.hookRepository = hookRepository;
        this.procedureParameterRepository = procedureParameterRepository;
        this.tagRepository = tagRepository;
        this.orchestrator = orchestrator;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Page<PipeResponse> search(String name, Pageable pageable) {
        Page<Pipe> page = name == null || name.isBlank() ? pipeRepository.findAll(pageable)
                : pipeRepository.findAllByNameContainingIgnoreCase(name.trim(), pageable);
        Page<PipeResponse> result = page.map(pipe -> PipeMapper.response(pipeRepository.findDetailedById(pipe.getId()).orElseThrow()));
        eventLogger.success("PIPE_PAGE_VIEWED", Map.of("page", result.getNumber(), "size", result.getSize(), "totalElements", result.getTotalElements()));
        return result;
    }

    @Transactional(readOnly = true)
    public PipeResponse get(long id) { return PipeMapper.response(find(id)); }

    @Transactional(readOnly = true)
    public PipeOptionsResponse options() {
        List<PipeOptionResponse> resources = new ArrayList<>();
        alertRepository.findAll(Sort.by("name")).forEach(alert -> resources.add(new PipeOptionResponse(
                alert.getId(), alert.getName(), alert.isEnabled(), PipeStepType.ALERT, List.of(), List.of())));
        for (Procedure procedure : procedureRepository.findAll(Sort.by("name"))) {
            List<String> outputs = outputRepository.findAllByTemplate_IdOrderByOutputOrderAscIdAsc(procedure.getTemplate().getId())
                    .stream().map(ProcedureTemplateOutputDefinition::getOutputKey).toList();
            List<String> inputs = parameterRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(procedure.getTemplate().getId())
                    .stream().filter(value -> value.getJavaType().equals(ProcedureArtifactInput.class.getName()))
                    .map(ProcedureTemplateParameterDefinition::getParameterKey).toList();
            resources.add(new PipeOptionResponse(procedure.getId(), procedure.getName(), procedure.isEnabled(),
                    PipeStepType.PROCEDURE, outputs, inputs));
        }
        return new PipeOptionsResponse(resources);
    }

    @Transactional
    public PipeResponse create(PipeCreateRequest request) {
        String name = required(request.name());
        ensureNameAvailable(name, null);
        validateHasSteps(request.enabled(), request.steps());
        Pipe pipe = pipeRepository.saveAndFlush(new Pipe(name, optional(request.description()), request.enabled(), request.allowConcurrentExecutions()));
        pipe.replaceTags(resolveTags(request.tagIds()));
        List<PipeStep> steps = steps(pipe, request.steps());
        validateComplete(request.enabled(), steps);
        pipe.replaceSteps(steps);
        pipeRepository.flush();
        eventLogger.successAfterCommit("PIPE_CREATED", data(pipe));
        return PipeMapper.response(pipe);
    }

    @Transactional
    public PipeResponse update(long id, PipeUpdateRequest request) {
        Pipe pipe = find(id);
        if (pipe.getVersion() != request.version())
            throw new ConflictException("Pipe was modified by another request; reload it and try again");

        String name = required(request.name());
        ensureNameAvailable(name, id);
        validateHasSteps(request.enabled(), request.steps());
        List<PipeStep> steps = steps(pipe, request.steps());
        validateComplete(request.enabled(), steps);
        pipe.update(name, optional(request.description()), request.enabled(), request.allowConcurrentExecutions());
        pipe.replaceTags(resolveTags(request.tagIds()));
        clearBindings(pipe);
        for (int index = 0; index < pipe.getSteps().size(); index++)
            pipe.getSteps().get(index).moveTemporarily(1_000_000 + index);
        pipeRepository.flush();
        pipe.clearSteps();
        pipeRepository.flush();
        pipe.replaceSteps(steps);
        pipeRepository.flush();
        eventLogger.successAfterCommit("PIPE_UPDATED", data(pipe));
        return PipeMapper.response(pipe);
    }

    @Transactional(readOnly = true)
    public UUID run(long id) {
        find(id);
        return orchestrator.triggerManual(id, eventLogger.currentUsername());
    }

    @Transactional(readOnly = true)
    public PipeDeletionImpactResponse deletionImpact(long id) {
        find(id);
        return new PipeDeletionImpactResponse(executionRepository.countByPipe_Id(id),
                hookRepository.countTargetsByPipeId(id), procedureParameterRepository.countByReferencedPipe_Id(id));
    }

    @Transactional
    public void delete(long id, long version) {
        Pipe pipe = find(id);
        if (pipe.getVersion() != version)
            throw new ConflictException("Pipe was modified by another request; reload it and try again");
        if (pipe.isEnabled())
            throw new ConflictException("PIPE_ACTIVE", "An enabled Pipe cannot be deleted", Map.of());
        if (orchestrator.isRunning(id))
            throw new ConflictException("PIPE_RUNNING", "A running Pipe cannot be deleted", Map.of());
        long hooks = hookRepository.countTargetsByPipeId(id);
        long procedures = procedureParameterRepository.countByReferencedPipe_Id(id);
        if (hooks + procedures > 0)
            throw new ConflictException("PIPE_IN_USE", "Pipe '" + pipe.getName() + "' is referenced", Map.of("hookReferences", Long.toString(hooks), "procedureReferences", Long.toString(procedures)));

        executionRepository.deleteAll(executionRepository.findAllByPipe_Id(id, Pageable.unpaged()).getContent());
        clearBindings(pipe);
        pipeRepository.delete(pipe);
        eventLogger.successAfterCommit("PIPE_DELETED", data(pipe));
    }

    private void clearBindings(Pipe pipe) {
        for (PipeStep step : pipe.getSteps())
            step.clearBindings();

        pipeRepository.flush();
    }

    private List<PipeStep> steps(Pipe pipe, List<PipeStepRequest> requests) {
        Set<String> keys = new HashSet<>();
        Map<String, PipeStep> byKey = new LinkedHashMap<>();
        Map<String, PipeStepRequest> requestByKey = new HashMap<>();
        List<PipeStep> result = new ArrayList<>();
        for (int position = 0; position < requests.size(); position++) {
            PipeStepRequest request = requests.get(position);
            String key = required(request.key());
            if (!keys.add(key))
                throw invalid("Step key '" + key + "' is duplicated");
            Duration timeout = request.timeout() == null ? Duration.ofMillis(PipeStep.DEFAULT_TIMEOUT_MILLIS) : request.timeout();
            if (timeout.isZero() || timeout.isNegative())
                throw invalid("Step timeout must be positive");
            List<String> continueOn = request.continueOn().stream().map(Enum::name).sorted().toList();
            PipeStep step;
            if (request.type() == PipeStepType.ALERT) {
                Alert alert = alertRepository.findById(request.resourceId())
                        .orElseThrow(() -> new ResourceNotFoundException("Alert " + request.resourceId() + " was not found"));
                if (!request.bindings().isEmpty())
                    throw invalid("Alert step '" + key + "' cannot declare artifact bindings");
                step = PipeStep.alert(pipe, key, position, alert, timeout.toMillis(), continueOn);
            } else {
                Procedure procedure = procedureRepository.findById(request.resourceId())
                        .orElseThrow(() -> new ResourceNotFoundException("Procedure " + request.resourceId() + " was not found"));
                step = PipeStep.procedure(pipe, key, position, procedure, timeout.toMillis(), continueOn);
            }
            result.add(step);
            byKey.put(key, step);
            requestByKey.put(key, request);
        }
        for (PipeStep target : result)
            bind(target, requestByKey.get(target.getStepKey()), byKey);

        return result;
    }

    private void bind(PipeStep target, PipeStepRequest request, Map<String, PipeStep> steps) {
        if (target.getStepType() != PipeStepType.PROCEDURE)
            return;
        Map<String, ProcedureTemplateParameterDefinition> parameters = new HashMap<>();
        parameterRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(target.getProcedure().getTemplate().getId())
                .forEach(value -> parameters.put(value.getParameterKey(), value));
        Set<String> targets = new HashSet<>();
        for (var binding : request.bindings()) {
            if (!targets.add(binding.targetParameterKey()))
                throw invalid("Target parameter '" + binding.targetParameterKey() + "' is bound more than once");
            ProcedureTemplateParameterDefinition parameter = parameters.get(binding.targetParameterKey());
            if (parameter == null || !parameter.getJavaType().equals(ProcedureArtifactInput.class.getName()))
                throw invalid("Target parameter '" + binding.targetParameterKey() + "' is not a ProcedureArtifactInput");
            PipeStep source = steps.get(binding.sourceStepKey());
            if (source == null || source.getPosition() >= target.getPosition())
                throw invalid("Binding source step '" + binding.sourceStepKey() + "' must be an earlier step");
            if (source.getStepType() != PipeStepType.PROCEDURE)
                throw invalid("Binding source step '" + binding.sourceStepKey() + "' must be a Procedure");
            ProcedureTemplateOutputDefinition output = outputRepository
                    .findAllByTemplate_IdOrderByOutputOrderAscIdAsc(source.getProcedure().getTemplate().getId()).stream()
                    .filter(value -> value.getOutputKey().equals(binding.sourceOutputKey())).findFirst()
                    .orElseThrow(() -> invalid("Output '" + binding.sourceOutputKey() + "' does not exist on source step '" + binding.sourceStepKey() + "'"));
            target.addBinding(new PipeStepBinding(target, parameter, source, output));
        }
    }

    private Pipe find(long id) {
        return pipeRepository.findDetailedById(id).orElseThrow(() -> new ResourceNotFoundException("Pipe " + id + " was not found"));
    }

    private Set<Tag> resolveTags(Set<Long> requestedIds) {
        Set<Long> ids = requestedIds == null ? Set.of() : Set.copyOf(requestedIds);
        if (ids.isEmpty())
            return Set.of();

        List<Tag> found = tagRepository.findAllByIdInAndScope(ids, TagScope.PIPE);
        if (found.size() != ids.size())
            throw invalid("One or more Pipe tags do not exist");

        return new java.util.LinkedHashSet<>(found);
    }

    private void ensureNameAvailable(String name, Long id) {
        boolean exists = id == null ? pipeRepository.existsByNameIgnoreCase(name) : pipeRepository.existsByNameIgnoreCaseAndIdNot(name, id);
        if (exists)
            throw new ConflictException("A Pipe named '" + name + "' already exists");
    }

    private static void validateHasSteps(boolean enabled, List<PipeStepRequest> steps) {
        if (enabled && steps.isEmpty())
            throw invalid("An enabled Pipe requires at least one step");
    }

    private void validateComplete(boolean enabled, List<PipeStep> steps) {
        if (!enabled)
            return;

        for (PipeStep step : steps) {
            if (step.getStepType() != PipeStepType.PROCEDURE)
                continue;

            Set<Long> boundParameters = step.getBindings().stream()
                    .map(binding -> binding.getTargetParameter().getId()).collect(java.util.stream.Collectors.toSet());
            Set<Long> fallbackParameters = procedureParameterRepository.findAllByOwnerIdOrdered(step.getProcedure().getId())
                    .stream().filter(value -> value.getSource() == app.alertify.alerts.template.annotation.AlertParameterSource.CONFIGURATION
                            || value.getSource() == app.alertify.alerts.template.annotation.AlertParameterSource.SECRET)
                    .map(value -> value.getTemplateParameter().getId()).collect(java.util.stream.Collectors.toSet());
            for (ProcedureTemplateParameterDefinition parameter : parameterRepository
                    .findAllByTemplate_IdOrderByParameterOrderAscIdAsc(step.getProcedure().getTemplate().getId())) {
                if (parameter.getJavaType().equals(ProcedureArtifactInput.class.getName())
                        && !boundParameters.contains(parameter.getId()) && !fallbackParameters.contains(parameter.getId()))
                    throw invalid("Enabled Pipe step '" + step.getStepKey() + "' has no binding or BINARY fallback for artifact input '" + parameter.getParameterKey() + "'");
            }
        }
    }

    private static String required(String value) { return value.trim(); }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static InvalidPipeRequestException invalid(String message) { return new InvalidPipeRequestException(message); }
    private static Map<String, Object> data(Pipe pipe) { return Map.<String, Object>of("pipeId", pipe.getId(), "pipeName", pipe.getName(), "enabled", pipe.isEnabled(), "stepCount", pipe.getSteps().size()); }
}
