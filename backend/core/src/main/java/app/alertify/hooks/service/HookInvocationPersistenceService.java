package app.alertify.hooks.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.hooks.api.HookInvocationResponse;
import app.alertify.hooks.model.Hook;
import app.alertify.hooks.model.HookInvocation;
import app.alertify.hooks.model.HookInvocationStatus;
import app.alertify.hooks.model.HookInvocationTarget;
import app.alertify.hooks.model.HookOutcome;
import app.alertify.hooks.model.HookTargetStatus;
import app.alertify.jpa.repository.HookInvocationRepository;
import app.alertify.jpa.repository.HookInvocationTargetRepository;
import app.alertify.logging.ApplicationEventLogger;

@Service
public class HookInvocationPersistenceService {

    private final HookInvocationRepository invocationRepository;
    private final HookInvocationTargetRepository targetRepository;
    private final HookMapper mapper;
    private final ApplicationEventLogger eventLogger;

    public HookInvocationPersistenceService(HookInvocationRepository invocationRepository, HookInvocationTargetRepository targetRepository, HookMapper mapper, ApplicationEventLogger eventLogger) {
        this.invocationRepository = invocationRepository;
        this.targetRepository = targetRepository;
        this.mapper = mapper;
        this.eventLogger = eventLogger;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HookInvocation accept(Hook hook, UUID invocationId) {
        HookInvocation invocation = new HookInvocation(hook, invocationId);
        hook.getTargets().forEach(target -> invocation.addTarget(new HookInvocationTarget(invocation, target)));
        return invocationRepository.saveAndFlush(invocation);
    }

    @Transactional(readOnly = true)
    public HookInvocation execution(UUID invocationId) {
        return invocationRepository.findByInvocationId(invocationId).orElseThrow(() -> new ResourceNotFoundException("Hook invocation was not found"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transitionTarget(Long targetId, HookTargetStatus status) {
        HookInvocationTarget target = targetRepository.findById(targetId).orElseThrow();
        target.transition(status);
        targetRepository.flush();
        if (status == HookTargetStatus.WAITING_ALERT || status == HookTargetStatus.WAITING_PROCEDURE)
            eventLogger.successAfterCommit("HOOK_INVOCATION_WAITING", Map.of("targetId", targetId, "resourceId", target.getResourceId()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeTarget(Long targetId, HookTargetStatus status, HookOutcome outcome, UUID executionId, String errorCode) {
        HookInvocationTarget target = targetRepository.findById(targetId).orElseThrow();
        target.complete(status, outcome, executionId, errorCode);
        targetRepository.flush();
        if (status == HookTargetStatus.ALERT_BUSY_TIMEOUT || status == HookTargetStatus.PROCEDURE_BUSY_TIMEOUT)
            eventLogger.failure("HOOK_INVOCATION_TIMEOUT", Map.of("targetId", targetId, "resourceId", target.getResourceId(), "errorCode", status.name()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HookInvocationStatus finish(UUID invocationId) {
        HookInvocation invocation = execution(invocationId);
        List<HookInvocationTarget> targets = invocation.getTargets();
        boolean allSuccess = targets.stream().allMatch(target -> target.getStatus() == HookTargetStatus.SUCCESS);
        boolean anyNonFailed = targets.stream().anyMatch(target -> target.getOutcome() == HookOutcome.SUCCESS || target.getOutcome() == HookOutcome.WARN);
        HookInvocationStatus status = allSuccess ? HookInvocationStatus.COMPLETED : anyNonFailed ? HookInvocationStatus.PARTIAL : HookInvocationStatus.FAILED;
        invocation.finish(status);
        invocationRepository.flush();
        eventLogger.successAfterCommit("HOOK_INVOCATION_COMPLETED", Map.of("invocationId", invocationId, "hookId", invocation.getHook().getId(), "status", status.name()));
        return status;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileInterrupted(UUID invocationId) {
        HookInvocation invocation = invocationRepository.findByInvocationId(invocationId).orElseThrow();
        for (HookInvocationTarget target : invocation.getTargets()) {
            if (target.getStatus() == HookTargetStatus.PENDING || target.getStatus() == HookTargetStatus.WAITING_ALERT
                    || target.getStatus() == HookTargetStatus.WAITING_PROCEDURE || target.getStatus() == HookTargetStatus.RUNNING)
                target.complete(HookTargetStatus.ERROR, HookOutcome.ERROR, target.getExecutionId(), "HOOK_INTERRUPTED");
        }
        boolean anyNonFailed = invocation.getTargets().stream().anyMatch(target -> target.getOutcome() == HookOutcome.SUCCESS || target.getOutcome() == HookOutcome.WARN);
        invocation.finish(anyNonFailed ? HookInvocationStatus.PARTIAL : HookInvocationStatus.FAILED);
        invocationRepository.flush();
    }

    @Transactional(readOnly = true)
    public HookInvocationResponse publicStatus(UUID publicId, UUID invocationId) {
        HookInvocation invocation = invocationRepository.findByHookPublicIdAndInvocationId(publicId, invocationId)
                .orElseThrow(() -> new ResourceNotFoundException("Hook invocation was not found"));
        return mapper.toResponse(invocation);
    }

    @Transactional(readOnly = true)
    public Page<HookInvocationResponse> history(Long hookId, Pageable pageable) {
        Page<HookInvocationResponse> result = invocationRepository.findAllByHook_Id(hookId, pageable).map(mapper::toResponse);
        eventLogger.successAfterCommit("HOOK_INVOCATION_HISTORY_VIEWED", Map.of("hookId", hookId, "page", result.getNumber(), "size", result.getSize()));
        return result;
    }

    @Transactional(readOnly = true)
    public List<HookInvocation> running() { return invocationRepository.findAllByStatus(HookInvocationStatus.RUNNING); }
}
