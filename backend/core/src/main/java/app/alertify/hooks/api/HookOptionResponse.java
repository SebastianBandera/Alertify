package app.alertify.hooks.api;

import app.alertify.hooks.model.HookTargetType;

public record HookOptionResponse(Long id, String name, boolean enabled, HookTargetType type) {
}
