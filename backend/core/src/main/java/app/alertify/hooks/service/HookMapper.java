package app.alertify.hooks.service;

import java.time.Duration;
import java.util.LinkedHashSet;

import org.springframework.stereotype.Component;

import app.alertify.hooks.api.HookInvocationResponse;
import app.alertify.hooks.api.HookInvocationTargetResponse;
import app.alertify.hooks.api.HookResponse;
import app.alertify.hooks.api.HookTargetResponse;
import app.alertify.hooks.model.Hook;
import app.alertify.hooks.model.HookInvocation;
import app.alertify.hooks.model.HookOutcome;
import app.alertify.hooks.model.HookTarget;
import app.alertify.hooks.model.HookTargetType;

@Component
public class HookMapper {

    public HookResponse toResponse(Hook hook) {
        return new HookResponse(
                hook.getId(), hook.getVersion(), hook.getPublicId(), hook.getName(), hook.getDescription(),
                hook.isEnabled(), hook.getMode(), hook.getTokenSecret() == null ? null : hook.getTokenSecret().getId(),
                hook.getTokenSecret() == null ? null : hook.getTokenSecret().getName(), hook.getMaxConcurrentInvocations(),
                hook.getRateLimitCount(), hook.getRateLimitWindowSeconds() == null ? null : Duration.ofSeconds(hook.getRateLimitWindowSeconds()),
                hook.getTargets().stream().map(this::toResponse).toList(), hook.getCreatedAt(), hook.getUpdatedAt()
        );
    }

    public HookTargetResponse toResponse(HookTarget target) {
        boolean alert = target.getTargetType() == HookTargetType.ALERT;
        return new HookTargetResponse(
                target.getId(), target.getTargetType(), alert ? target.getAlert().getId() : target.getProcedure().getId(),
                alert ? target.getAlert().getName() : target.getProcedure().getName(),
                alert ? target.getAlert().isEnabled() : target.getProcedure().isEnabled(), target.getPosition(),
                target.getContinueOn().stream().map(HookOutcome::valueOf).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                target.getBusyWaitTimeoutMillis() == null ? null : Duration.ofMillis(target.getBusyWaitTimeoutMillis())
        );
    }

    public HookInvocationResponse toResponse(HookInvocation invocation) {
        return new HookInvocationResponse(
                invocation.getInvocationId(), invocation.getHookPublicId(), invocation.getHookName(), invocation.getMode(),
                invocation.getStatus(), invocation.getAcceptedAt(), invocation.getFinishedAt(),
                invocation.getTargets().stream().map(target -> new HookInvocationTargetResponse(
                        target.getTargetType(), target.getResourceName(), target.getPosition(), target.getStatus(), target.getExecutionId()
                )).toList()
        );
    }
}
