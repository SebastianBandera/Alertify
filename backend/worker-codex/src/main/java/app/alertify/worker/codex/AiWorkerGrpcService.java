package app.alertify.worker.codex;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.google.protobuf.Empty;

import app.alertify.worker.ai.grpc.AiSettings;
import app.alertify.worker.ai.grpc.AiWorkerServiceGrpc;
import app.alertify.worker.ai.grpc.BeginBrowserLoginRequest;
import app.alertify.worker.ai.grpc.BrowserLoginResponse;
import app.alertify.worker.ai.grpc.CompleteBrowserLoginRequest;
import app.alertify.worker.ai.grpc.DeviceLoginResponse;
import app.alertify.worker.ai.grpc.ModuleStateResponse;
import app.alertify.worker.ai.grpc.OperationResponse;
import app.alertify.worker.ai.grpc.RefreshResponse;
import app.alertify.worker.ai.grpc.UpdateSettingsRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@Component
class AiWorkerGrpcService extends AiWorkerServiceGrpc.AiWorkerServiceImplBase {

    private final CodexSessionService sessions;

    AiWorkerGrpcService(CodexSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void getModuleState(Empty request, StreamObserver<ModuleStateResponse> observer) {
        execute(observer, () -> {
            PersistentAiState state = sessions.state();
            OAuthSession session = state.session();
            return ModuleStateResponse.newBuilder()
                    .setEnabled(state.settings().enabled())
                    .setAuthMode(session == null ? "" : session.authMode())
                    .setSessionStatus(sessions.sessionStatus())
                    .setAccountLabel(session == null ? "" : nullSafe(session.accountLabel()))
                    .setExpiresAt(session == null ? "" : instant(session.expiresAt()))
                    .setRefreshExpiresAt(session == null ? "" : instant(session.refreshExpiresAt()))
                    .setLastRefreshedAt(session == null ? "" : instant(session.lastRefreshedAt()))
                    .setRefreshPolicy(state.settings().refreshPolicy())
                    .setLoginPending(sessions.loginPending())
                    .setPendingLoginMode(sessions.pendingMode())
                    .build();
        });
    }

    @Override
    public void getSettings(Empty request, StreamObserver<AiSettings> observer) {
        execute(observer, () -> response(sessions.state().settings()));
    }

    @Override
    public void updateSettings(UpdateSettingsRequest request, StreamObserver<AiSettings> observer) {
        execute(observer, () -> response(sessions.updateSettings(new AiSettingsState(
                request.getSettings().getEnabled(), request.getSettings().getProvider(), request.getSettings().getModel(),
                request.getSettings().getReasoningEffort(), request.getSettings().getAgentInstructions(),
                request.getSettings().getRefreshPolicy()))));
    }

    @Override
    public void beginBrowserLogin(BeginBrowserLoginRequest request, StreamObserver<BrowserLoginResponse> observer) {
        execute(observer, () -> {
            BrowserLoginStart start = sessions.beginBrowserLogin(request.getRedirectUri(), request.getLoginMode(), request.getAdminSubject());
            PendingBrowserLogin login = start.login();
            return BrowserLoginResponse.newBuilder()
                    .setAuthorizationUrl(sessions.browserAuthorizationUrl(login))
                    .setExpiresAt(instant(login.expiresAt()))
                    .setCallbackTicket(start.callbackTicket())
                    .build();
        });
    }

    @Override
    public void completeBrowserLogin(CompleteBrowserLoginRequest request, StreamObserver<OperationResponse> observer) {
        execute(observer, () -> {
            sessions.completeBrowserLogin(request.getCode(), request.getState(), request.getScope(), request.getClientId(), request.getCallbackTicket());
            return success("Browser login completed");
        });
    }

    @Override
    public void beginDeviceLogin(Empty request, StreamObserver<DeviceLoginResponse> observer) {
        execute(observer, () -> device(sessions.beginDeviceLogin()));
    }

    @Override
    public void getDeviceLogin(Empty request, StreamObserver<DeviceLoginResponse> observer) {
        execute(observer, () -> device(sessions.deviceLogin()));
    }

    @Override
    public void cancelLogin(Empty request, StreamObserver<OperationResponse> observer) {
        execute(observer, () -> {
            sessions.cancelLogin();
            return success("Login attempt cancelled");
        });
    }

    @Override
    public void refreshSession(Empty request, StreamObserver<RefreshResponse> observer) {
        execute(observer, () -> {
            CodexSessionService.RefreshResult result = sessions.refresh();
            return RefreshResponse.newBuilder().setSuccessful(true)
                    .setPreviousExpiresAt(instant(result.previousExpiresAt()))
                    .setExpiresAt(instant(result.expiresAt()))
                    .setRefreshExpiresAt(instant(result.refreshExpiresAt()))
                    .setLastRefreshedAt(instant(result.lastRefreshedAt()))
                    .setMessage("Session refreshed").build();
        });
    }

    @Override
    public void logout(Empty request, StreamObserver<OperationResponse> observer) {
        execute(observer, () -> {
            boolean revoked = sessions.logout();
            if (revoked)
                return success("Session closed");

            return OperationResponse.newBuilder().setSuccessful(false)
                    .setMessage("Local session cleared; remote token revocation could not be confirmed").build();
        });
    }

    @Override
    public void testConnection(Empty request, StreamObserver<OperationResponse> observer) {
        execute(observer, () -> {
            sessions.testConnection();
            return success("Connection successful");
        });
    }

    private static AiSettings response(AiSettingsState settings) {
        return AiSettings.newBuilder().setEnabled(settings.enabled()).setProvider(settings.provider())
                .setModel(settings.model()).setReasoningEffort(settings.reasoningEffort())
                .setAgentInstructions(nullSafe(settings.agentInstructions())).setRefreshPolicy(settings.refreshPolicy()).build();
    }

    private static DeviceLoginResponse device(PendingDeviceLogin login) {
        if (login == null)
            return DeviceLoginResponse.newBuilder().setStatus("NONE").build();
        return DeviceLoginResponse.newBuilder().setStatus(login.status()).setVerificationUri(CodexSessionService.DEVICE_VERIFICATION_URI)
                .setUserCode(login.userCode()).setExpiresAt(instant(login.expiresAt()))
                .setIntervalSeconds(login.intervalSeconds()).setError(nullSafe(login.error())).build();
    }

    private static OperationResponse success(String message) {
        return OperationResponse.newBuilder().setSuccessful(true).setMessage(message).build();
    }

    private static String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static <T> void execute(StreamObserver<T> observer, Operation<T> operation) {
        try {
            observer.onNext(operation.run());
            observer.onCompleted();
        } catch (IllegalArgumentException exception) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(exception.getMessage()).asRuntimeException());
        } catch (IllegalStateException exception) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription(exception.getMessage()).asRuntimeException());
        } catch (RuntimeException exception) {
            observer.onError(Status.INTERNAL.withDescription("The AI worker operation failed").asRuntimeException());
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
