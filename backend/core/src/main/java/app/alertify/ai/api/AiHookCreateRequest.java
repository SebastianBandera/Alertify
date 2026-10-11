package app.alertify.ai.api;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import app.alertify.hooks.api.HookCreateRequest;
import app.alertify.hooks.model.HookMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AiHookCreateRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 4000) String description,
    @NotNull HookMode mode,
    @Schema(description = "Optional positive token secret ID; use null or 0 for an unprotected hook") Long tokenSecretId,
    @Schema(description = "Optional positive concurrency limit; use null or 0 for no limit") Integer maxConcurrentInvocations,
    @Schema(description = "Optional positive request count; configure it together with rateLimitWindow, or use null or 0 for both") Integer rateLimitCount,
    @Schema(description = "Optional positive ISO-8601 duration, for example PT1M; configure it together with rateLimitCount, or use null or an empty string for both") String rateLimitWindow,
    @NotNull List<@NotNull @Valid AiHookTarget> targets,
    @NotNull @Size(max = 100) Set<@Positive Long> tagIds
) {

    public HookCreateRequest toRequest() {
        Integer count = AiRequestValues.optionalPositive(rateLimitCount);
        Duration window = AiRequestValues.optionalDuration(rateLimitWindow, "rateLimitWindow");
        requireRateLimitPair(count, window);
        return new HookCreateRequest(name, description, mode, AiRequestValues.optionalPositive(tokenSecretId),
                AiRequestValues.optionalPositive(maxConcurrentInvocations), count, window,
                targets.stream().map(AiHookTarget::toRequest).toList(), tagIds);
    }

    static void requireRateLimitPair(Integer count, Duration window) {
        if ((count == null) != (window == null))
            throw new IllegalArgumentException("rateLimitCount and rateLimitWindow must be configured together");

        if (window != null && window.getSeconds() <= 0)
            throw new IllegalArgumentException("rateLimitWindow must be at least one second");
    }
}
