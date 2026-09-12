package app.alertify.hooks.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.model.Alert;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidHookRequestException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.hooks.api.HookCreateRequest;
import app.alertify.hooks.api.HookDeletionImpactResponse;
import app.alertify.hooks.api.HookOptionResponse;
import app.alertify.hooks.api.HookOptionsResponse;
import app.alertify.hooks.api.HookResponse;
import app.alertify.hooks.api.HookSecretOptionResponse;
import app.alertify.hooks.api.HookTargetRequest;
import app.alertify.hooks.api.HookUpdateRequest;
import app.alertify.hooks.model.Hook;
import app.alertify.hooks.model.HookTarget;
import app.alertify.hooks.model.HookTargetType;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.HookInvocationRepository;
import app.alertify.jpa.repository.HookInvocationTargetRepository;
import app.alertify.jpa.repository.HookRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.model.Procedure;
import app.alertify.services.secret.SecretEncryptionService;

@Service
public class HookManagementService {

    private final HookRepository hookRepository;
    private final AlertRepository alertRepository;
    private final ProcedureRepository procedureRepository;
    private final ApplicationSecretRepository secretRepository;
    private final HookInvocationRepository invocationRepository;
    private final HookInvocationTargetRepository invocationTargetRepository;
    private final SecretEncryptionService encryptionService;
    private final HookMapper mapper;
    private final ApplicationEventLogger eventLogger;

    public HookManagementService(HookRepository hookRepository, AlertRepository alertRepository, ProcedureRepository procedureRepository, ApplicationSecretRepository secretRepository, HookInvocationRepository invocationRepository, HookInvocationTargetRepository invocationTargetRepository, SecretEncryptionService encryptionService, HookMapper mapper, ApplicationEventLogger eventLogger) {
        this.hookRepository = hookRepository;
        this.alertRepository = alertRepository;
        this.procedureRepository = procedureRepository;
        this.secretRepository = secretRepository;
        this.invocationRepository = invocationRepository;
        this.invocationTargetRepository = invocationTargetRepository;
        this.encryptionService = encryptionService;
        this.mapper = mapper;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Page<HookResponse> search(String name, Pageable pageable) {
        Page<HookResponse> result = (name == null || name.isBlank() ? hookRepository.findAll(pageable)
                : hookRepository.findAllByNameContainingIgnoreCase(name.trim(), pageable)).map(mapper::toResponse);
        eventLogger.successAfterCommit("HOOK_PAGE_VIEWED", Map.of("page", result.getNumber(), "size", result.getSize(), "totalElements", result.getTotalElements()));
        return result;
    }

    @Transactional(readOnly = true)
    public HookResponse get(Long id) { return mapper.toResponse(find(id)); }

    @Transactional(readOnly = true)
    public HookOptionsResponse options() {
        List<HookOptionResponse> targets = new ArrayList<>();
        alertRepository.findAll(Sort.by("name")).forEach(value -> targets.add(new HookOptionResponse(value.getId(), value.getName(), value.isEnabled(), HookTargetType.ALERT)));
        procedureRepository.findAll(Sort.by("name")).forEach(value -> targets.add(new HookOptionResponse(value.getId(), value.getName(), value.isEnabled(), HookTargetType.PROCEDURE)));
        List<HookSecretOptionResponse> secrets = secretRepository.findAll(Sort.by("name")).stream()
                .filter(value -> value.getValueType() == SecretValueType.STRING)
                .map(value -> new HookSecretOptionResponse(value.getId(), value.getName(), encryptionService.isRecoverable(value))).toList();
        eventLogger.success("HOOK_OPTIONS_VIEWED", Map.of("targetCount", targets.size(), "secretCount", secrets.size()));
        return new HookOptionsResponse(targets, secrets);
    }

    @Transactional
    public HookResponse create(HookCreateRequest request) {
        String name = required(request.name());
        ensureNameAvailable(name, null);
        Limits limits = limits(request.maxConcurrentInvocations(), request.rateLimitCount(), request.rateLimitWindow());
        Hook hook = new Hook(name, optional(request.description()), request.mode(), secret(request.tokenSecretId()), request.maxConcurrentInvocations(), limits.count(), limits.windowSeconds());
        List<HookTarget> targets = targets(hook, request.targets());
        hook.replaceTargets(targets);
        Hook saved = hookRepository.saveAndFlush(hook);
        eventLogger.successAfterCommit("HOOK_CREATED", data(saved));
        return mapper.toResponse(saved);
    }

    @Transactional
    public HookResponse update(Long id, HookUpdateRequest request) {
        Hook hook = find(id);
        if (hook.getVersion() != request.version())
            throw new ConflictException("Hook was modified by another request; reload it and try again");

        String name = required(request.name());
        ensureNameAvailable(name, id);
        if (request.enabled() && request.targets().isEmpty())
            throw invalid("An enabled hook requires at least one target");

        Limits limits = limits(request.maxConcurrentInvocations(), request.rateLimitCount(), request.rateLimitWindow());
        hook.update(name, optional(request.description()), request.enabled(), request.mode(), secret(request.tokenSecretId()), request.maxConcurrentInvocations(), limits.count(), limits.windowSeconds());
        List<HookTarget> values = targets(hook, request.targets());
        for (int index = 0; index < hook.getTargets().size(); index++)
            hook.getTargets().get(index).moveTemporarily(1_000_000 + index);

        hookRepository.flush();
        hook.clearTargets();
        hookRepository.flush();
        hook.replaceTargets(values);
        hookRepository.flush();
        eventLogger.successAfterCommit("HOOK_UPDATED", data(hook));
        return mapper.toResponse(hook);
    }

    @Transactional
    public HookResponse rotate(Long id, long version) {
        Hook hook = find(id);
        if (hook.getVersion() != version)
            throw new ConflictException("Hook was modified by another request; reload it and try again");

        hook.rotatePublicId();
        hookRepository.flush();
        eventLogger.successAfterCommit("HOOK_ROTATED", data(hook));
        return mapper.toResponse(hook);
    }

    @Transactional(readOnly = true)
    public HookDeletionImpactResponse deletionImpact(Long id) {
        find(id);
        return new HookDeletionImpactResponse(invocationRepository.countByHook_Id(id), invocationTargetRepository.countByHookId(id));
    }

    @Transactional
    public void delete(Long id, long version) {
        Hook hook = find(id);
        if (hook.getVersion() != version)
            throw new ConflictException("Hook was modified by another request; reload it and try again");

        if (hook.isEnabled())
            throw new ConflictException("HOOK_ACTIVE", "An enabled hook cannot be deleted", Map.of());

        Map<String, Object> data = data(hook);
        data.put("invocationCount", invocationRepository.countByHook_Id(id));
        hookRepository.delete(hook);
        hookRepository.flush();
        eventLogger.successAfterCommit("HOOK_DELETED", data);
    }

    private List<HookTarget> targets(Hook hook, List<HookTargetRequest> requests) {
        Set<String> unique = new HashSet<>();
        List<HookTarget> result = new ArrayList<>();
        for (int position = 0; position < requests.size(); position++) {
            HookTargetRequest request = requests.get(position);
            String key = request.type() + ":" + request.resourceId();
            if (!unique.add(key))
                throw invalid("A hook cannot contain the same resource more than once");

            List<String> outcomes = request.continueOn().stream().map(Enum::name).sorted().toList();
            Duration timeout = request.busyWaitTimeout() == null ? Duration.ofMillis(HookTarget.DEFAULT_BUSY_WAIT_MILLIS) : request.busyWaitTimeout();
            if (timeout.isZero() || timeout.isNegative())
                throw invalid("Target busy wait timeout must be positive");

            if (request.type() == HookTargetType.ALERT) {
                Alert alert = alertRepository.findById(request.resourceId()).orElseThrow(() -> new ResourceNotFoundException("Alert " + request.resourceId() + " was not found"));
                result.add(HookTarget.alert(hook, alert, position, outcomes, timeout.toMillis()));
            } else {
                Procedure procedure = procedureRepository.findById(request.resourceId()).orElseThrow(() -> new ResourceNotFoundException("Procedure " + request.resourceId() + " was not found"));
                result.add(HookTarget.procedure(hook, procedure, position, outcomes, timeout.toMillis()));
            }
        }
        return result;
    }

    private Limits limits(Integer maximum, Integer count, Duration window) {
        if ((count == null) != (window == null))
            throw invalid("Rate limit count and window must be configured together");

        if (window != null && (window.isZero() || window.isNegative() || window.getSeconds() <= 0))
            throw invalid("Rate limit window must be at least one second");

        return new Limits(count, window == null ? null : window.getSeconds());
    }

    private ApplicationSecret secret(Long id) {
        if (id == null)
            return null;

        ApplicationSecret secret = secretRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Secret " + id + " was not found"));
        if (secret.getValueType() != SecretValueType.STRING)
            throw invalid("Hook token secret must be a STRING secret");

        return secret;
    }

    private Hook find(Long id) { return hookRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Hook " + id + " was not found")); }

    private void ensureNameAvailable(String name, Long id) {
        boolean exists = id == null ? hookRepository.existsByNameIgnoreCase(name) : hookRepository.existsByNameIgnoreCaseAndIdNot(name, id);
        if (exists)
            throw new ConflictException("A hook named '" + name + "' already exists");
    }

    private static Map<String, Object> data(Hook hook) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hookId", hook.getId());
        data.put("hookName", hook.getName());
        data.put("publicId", hook.getPublicId());
        data.put("enabled", hook.isEnabled());
        data.put("targetCount", hook.getTargets().size());
        data.put("tokenProtected", hook.getTokenSecret() != null);
        return data;
    }

    private static String required(String value) { return value.trim(); }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static InvalidHookRequestException invalid(String message) { return new InvalidHookRequestException(message); }
    private record Limits(Integer count, Long windowSeconds) { }
}
