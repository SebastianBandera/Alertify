package app.alertify.codex;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.google.protobuf.Empty;

import app.alertify.grpc.WorkerGrpcChannelFactory;
import app.alertify.grpc.discovery.AvailableWorker;
import app.alertify.grpc.discovery.WorkerAvailabilityService;
import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.worker.ai.grpc.AiWorkerServiceGrpc;
import app.alertify.worker.ai.grpc.BeginBrowserLoginRequest;
import app.alertify.worker.ai.grpc.CompleteBrowserLoginRequest;
import app.alertify.worker.ai.grpc.UpdateSettingsRequest;
import app.alertify.worker.contract.WorkerCapability;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

@Service
public class AiWorkerClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final Set<WorkerCapability> CAPABILITIES = Set.of(WorkerCapability.AI, WorkerCapability.CODEX);

    private final WorkerAvailabilityService availability;
    private final WorkerGrpcChannelFactory channels;

    public AiWorkerClient(WorkerAvailabilityService availability, WorkerGrpcChannelFactory channels) {
        this.availability = availability;
        this.channels = channels;
    }

    public app.alertify.worker.ai.grpc.ModuleStateResponse state() {
        return invoke(stub -> stub.getModuleState(Empty.getDefaultInstance()));
    }

    public app.alertify.worker.ai.grpc.AiSettings settings() {
        return invoke(stub -> stub.getSettings(Empty.getDefaultInstance()));
    }

    public app.alertify.worker.ai.grpc.AiSettings update(app.alertify.worker.ai.grpc.AiSettings settings) {
        return invoke(stub -> stub.updateSettings(UpdateSettingsRequest.newBuilder().setSettings(settings).build()));
    }

    public app.alertify.worker.ai.grpc.BrowserLoginResponse beginBrowser(String redirectUri, String loginMode, String adminSubject) {
        return invoke(stub -> stub.beginBrowserLogin(BeginBrowserLoginRequest.newBuilder().setRedirectUri(redirectUri)
                .setLoginMode(loginMode).setAdminSubject(adminSubject).build()));
    }

    public app.alertify.worker.ai.grpc.OperationResponse completeBrowser(String code, String state, String scope, String clientId, String callbackTicket) {
        return invoke(stub -> stub.completeBrowserLogin(CompleteBrowserLoginRequest.newBuilder().setCode(code)
                .setState(state).setScope(scope).setClientId(clientId).setCallbackTicket(callbackTicket).build()));
    }

    public app.alertify.worker.ai.grpc.DeviceLoginResponse beginDevice() {
        return invoke(stub -> stub.beginDeviceLogin(Empty.getDefaultInstance()));
    }

    public app.alertify.worker.ai.grpc.DeviceLoginResponse device() {
        return invoke(stub -> stub.getDeviceLogin(Empty.getDefaultInstance()));
    }

    public app.alertify.worker.ai.grpc.OperationResponse cancel() {
        return invoke(stub -> stub.cancelLogin(Empty.getDefaultInstance()));
    }

    public app.alertify.worker.ai.grpc.RefreshResponse refresh() {
        return invoke(stub -> stub.refreshSession(Empty.getDefaultInstance()));
    }

    public app.alertify.worker.ai.grpc.OperationResponse logout() {
        return invoke(stub -> stub.logout(Empty.getDefaultInstance()));
    }

    public app.alertify.worker.ai.grpc.OperationResponse test() {
        return invoke(stub -> stub.testConnection(Empty.getDefaultInstance()));
    }

    private <T> T invoke(GrpcCall<T> call) {
        WorkerEndpoint endpoint = endpoint();
        ManagedChannel channel = channels.create(endpoint);
        try {
            return call.call(AiWorkerServiceGrpc.newBlockingStub(channel).withDeadlineAfter(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (StatusRuntimeException exception) {
            String message = exception.getStatus().getDescription();
            String code = switch (exception.getStatus().getCode()) {
                case INVALID_ARGUMENT -> "AI_INVALID_REQUEST";
                case FAILED_PRECONDITION -> "AI_OPERATION_CONFLICT";
                default -> "AI_WORKER_OPERATION_FAILED";
            };
            throw new AiWorkerException(code, message == null ? "The AI worker operation failed" : message);
        } finally {
            channel.shutdownNow();
        }
    }

    WorkerEndpoint endpoint() {
        Set<AvailableWorker> workers = availability.availableWorkersWithAll(CAPABILITIES);
        if (workers.isEmpty())
            throw new AiWorkerException("AI_WORKER_UNAVAILABLE", "The AI module is unavailable because its worker is not active");
        if (workers.size() > 1)
            throw new AiWorkerException("AI_WORKER_AMBIGUOUS", "The AI module requires exactly one active Codex worker");

        AvailableWorker worker = workers.iterator().next();
        return new WorkerEndpoint(worker.ipAddress(), worker.port());
    }

    @FunctionalInterface
    private interface GrpcCall<T> {
        T call(AiWorkerServiceGrpc.AiWorkerServiceBlockingStub stub);
    }
}
