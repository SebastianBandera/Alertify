package app.alertify.hooks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.alertify.alerts.model.Alert;
import app.alertify.hooks.HookInvocationRejectedException;
import app.alertify.hooks.model.Hook;
import app.alertify.hooks.model.HookTarget;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.repository.HookRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.services.secret.SecretEncryptionService;

class HookInvocationServiceTest {

    private final HookRepository hooks = mock(HookRepository.class);
    private final SecretEncryptionService encryption = mock(SecretEncryptionService.class);
    private final HookAdmissionService admission = mock(HookAdmissionService.class);
    private final HookInvocationPersistenceService persistence = mock(HookInvocationPersistenceService.class);
    private final HookCoordinator coordinator = mock(HookCoordinator.class);
    private final ApplicationEventLogger eventLogger = mock(ApplicationEventLogger.class);
    private HookInvocationService service;

    @BeforeEach
    void setUp() {
        service = new HookInvocationService(hooks, encryption, admission, persistence, coordinator, eventLogger);
    }

    @Test
    void rejectsMissingOrInvalidTokenBeforeAdmission() {
        UUID publicId = UUID.randomUUID();
        Hook hook = executableHook();
        ApplicationSecret secret = mock(ApplicationSecret.class);
        when(hook.getTokenSecret()).thenReturn(secret);
        when(hooks.findByPublicIdAndEnabledTrue(publicId)).thenReturn(Optional.of(hook));
        when(encryption.decrypt(secret)).thenReturn("correct-token");

        assertThatThrownBy(() -> service.invoke(publicId, null))
                .isInstanceOfSatisfying(HookInvocationRejectedException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(401);
                    assertThat(exception.getCode()).isEqualTo("HOOK_TOKEN_INVALID");
                });
        assertThatThrownBy(() -> service.invoke(publicId, "wrong-token"))
                .isInstanceOfSatisfying(HookInvocationRejectedException.class, exception -> assertThat(exception.getStatus().value()).isEqualTo(401));
        verify(admission, never()).admit(any(), any());
    }

    @Test
    void acceptsCorrectTokenAndSubmitsSnapshotExecution() {
        UUID publicId = UUID.randomUUID();
        Hook hook = executableHook();
        ApplicationSecret secret = mock(ApplicationSecret.class);
        when(hook.getTokenSecret()).thenReturn(secret);
        when(hooks.findByPublicIdAndEnabledTrue(publicId)).thenReturn(Optional.of(hook));
        when(encryption.decrypt(secret)).thenReturn("correct-token");
        when(admission.limited(hook)).thenReturn(true);

        UUID invocationId = service.invoke(publicId, "correct-token").invocationId();

        verify(admission).admit(hook, invocationId);
        verify(persistence).accept(hook, invocationId);
        verify(coordinator).submit(invocationId, 42L, true);
    }

    @Test
    void hidesDisabledAndUnknownHooksBehindNotFound() {
        UUID publicId = UUID.randomUUID();
        when(hooks.findByPublicIdAndEnabledTrue(publicId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invoke(publicId, null))
                .isInstanceOfSatisfying(HookInvocationRejectedException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(404);
                    assertThat(exception.getCode()).isEqualTo("HOOK_NOT_FOUND");
                });
    }

    @Test
    void rollsBackBothLimitEntriesWhenSnapshotPersistenceFails() {
        UUID publicId = UUID.randomUUID();
        Hook hook = executableHook();
        when(hooks.findByPublicIdAndEnabledTrue(publicId)).thenReturn(Optional.of(hook));
        when(admission.limited(hook)).thenReturn(true);
        when(persistence.accept(eq(hook), any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.invoke(publicId, null)).isInstanceOf(IllegalStateException.class);

        verify(admission).rollback(eq(42L), any());
        verify(coordinator, never()).submit(any(), eq(42L), eq(true));
    }

    private static Hook executableHook() {
        Hook hook = mock(Hook.class);
        HookTarget target = mock(HookTarget.class);
        Alert alert = mock(Alert.class);
        when(hook.getId()).thenReturn(42L);
        when(hook.getPublicId()).thenReturn(UUID.randomUUID());
        when(hook.getName()).thenReturn("Deployment hook");
        when(hook.getTargets()).thenReturn(List.of(target));
        when(target.getTargetType()).thenReturn(app.alertify.hooks.model.HookTargetType.ALERT);
        when(target.getAlert()).thenReturn(alert);
        when(alert.isEnabled()).thenReturn(true);
        return hook;
    }
}
