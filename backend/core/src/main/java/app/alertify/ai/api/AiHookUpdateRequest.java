package app.alertify.ai.api;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import app.alertify.hooks.api.HookUpdateRequest;
import app.alertify.hooks.model.HookMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AiHookUpdateRequest(
    @PositiveOrZero long version,
    @NotBlank @Size(max = 255) String name,
    @Size(max = 4000) String description,
    boolean enabled,
    @NotNull HookMode mode,
    @Schema(description = "Optional positive token secret ID; use null or 0 to remove token protection") Long tokenSecretId,
    @Schema(description = "Optional positive concurrency limit; use null or 0 for no limit") Integer maxConcurrentInvocations,
    @Schema(description = "Optional positive request count; configure it together with rateLimitWindow, or use null or 0 for both") Integer rateLimitCount,
    @Schema(description = "Optional positive ISO-8601 duration, for example PT1M; configure it together with rateLimitCount, or use null or an empty string for both") String rateLimitWindow,
    @NotNull List<@NotNull @Valid AiHookTarget> targets,
    @NotNull @Size(max = 100) Set<@Positive Long> tagIds
) {

    public HookUpdateRequest toRequest() {
        Integer count = AiRequestValues.optionalPositive(rateLimitCount);
        Duration window = AiRequestValues.optionalDuration(rateLimitWindow, "rateLimitWindow");
        AiHookCreateRequest.requireRateLimitPair(count, window);
        return new HookUpdateRequest(version, name, description, enabled, mode,
                AiRequestValues.optionalPositive(tokenSecretId), AiRequestValues.optionalPositive(maxConcurrentInvocations),
                count, window, targets.stream().map(AiHookTarget::toRequest).toList(), tagIds);
    }
}
