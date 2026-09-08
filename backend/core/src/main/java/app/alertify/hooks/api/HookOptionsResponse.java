package app.alertify.hooks.api;

import java.util.List;

public record HookOptionsResponse(
    List<HookOptionResponse> targets,
    List<HookSecretOptionResponse> secrets
) {
}
