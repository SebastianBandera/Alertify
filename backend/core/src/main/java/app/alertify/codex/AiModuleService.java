package app.alertify.codex;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import app.alertify.worker.ai.grpc.AiSettings;

@Service
public class AiModuleService {

    private final AiWorkerClient client;
    private final BrowserExtensionPackageService extensions;
    private final String directCallbackUri;
    private final String extensionCallbackUri;

    public AiModuleService(AiWorkerClient client, BrowserExtensionPackageService extensions, @Value("${ai.oauth.callback-uri}") String directCallbackUri, @Value("${ai.oauth.extension-callback-uri}") String extensionCallbackUri) {
        this.client = client;
        this.extensions = extensions;
        this.directCallbackUri = directCallbackUri;
        this.extensionCallbackUri = extensionCallbackUri;
    }

    public ModuleResponse module() {
        try {
            var state = client.state();
            var settings = client.settings();
            return new ModuleResponse(true, "", state.getEnabled(), state.getAuthMode(), state.getSessionStatus(),
                    state.getAccountLabel(), state.getExpiresAt(), state.getRefreshExpiresAt(), state.getLastRefreshedAt(),
                    state.getRefreshPolicy(), state.getLoginPending(), state.getPendingLoginMode(), extensions.instanceId(),
                    extensions.protocolVersion(), settingsResponse(settings));
        } catch (AiWorkerException exception) {
            if (!java.util.Set.of("AI_WORKER_UNAVAILABLE", "AI_WORKER_AMBIGUOUS").contains(exception.getCode()))
                throw exception;

            return new ModuleResponse(false, exception.getCode(), false, "", "UNAVAILABLE", "", "", "", "", "", false, "", extensions.instanceId(), extensions.protocolVersion(), defaultSettings());
        }
    }

    public SettingsResponse update(SettingsRequest request) {
        AiSettings settings = AiSettings.newBuilder().setEnabled(request.enabled()).setProvider(request.provider())
                .setModel(request.model()).setReasoningEffort(request.reasoningEffort())
                .setAgentInstructions(request.agentInstructions() == null ? "" : request.agentInstructions())
                .setRefreshPolicy(request.refreshPolicy()).build();
        return settingsResponse(client.update(settings));
    }

    public BrowserLoginResponse beginBrowser(String mode, String adminSubject) {
        String normalizedMode = mode == null ? "" : mode.toUpperCase(java.util.Locale.ROOT);
        String callbackUri = "EXTENSION".equals(normalizedMode) ? extensionCallbackUri : directCallbackUri;
        var response = client.beginBrowser(callbackUri, normalizedMode, adminSubject);
        return new BrowserLoginResponse(normalizedMode, response.getAuthorizationUrl(), response.getExpiresAt(),
                response.getCallbackTicket(), "EXTENSION".equals(normalizedMode) ? extensionCallbackUri : "",
                extensions.instanceId(), extensions.protocolVersion());
    }

    public void completeBrowser(String code, String state, String scope, String clientId) {
        client.completeBrowser(code, state, scope, clientId, "");
    }

    public OperationResponse completeExtension(String callbackTicket, String code, String state, String scope, String clientId) {
        return operation(client.completeBrowser(code, state, scope, clientId, callbackTicket));
    }

    public BrowserExtensionPackageService.ExtensionPackage extension(String browser) {
        return extensions.build(browser);
    }

    public DeviceLoginResponse beginDevice() {
        return deviceResponse(client.beginDevice());
    }

    public DeviceLoginResponse device() {
        return deviceResponse(client.device());
    }

    public OperationResponse cancel() {
        return operation(client.cancel());
    }

    public RefreshResponse refresh() {
        var response = client.refresh();
        return new RefreshResponse(response.getSuccessful(), response.getPreviousExpiresAt(), response.getExpiresAt(),
                response.getRefreshExpiresAt(), response.getLastRefreshedAt(), response.getMessage());
    }

    public OperationResponse logout() {
        return operation(client.logout());
    }

    public OperationResponse test() {
        return operation(client.test());
    }

    private static SettingsResponse settingsResponse(AiSettings settings) {
        return new SettingsResponse(settings.getEnabled(), settings.getProvider(), settings.getModel(),
                settings.getReasoningEffort(), settings.getAgentInstructions(), settings.getRefreshPolicy());
    }

    private static SettingsResponse defaultSettings() {
        return new SettingsResponse(false, "CODEX", "", "", "", "");
    }

    private static DeviceLoginResponse deviceResponse(app.alertify.worker.ai.grpc.DeviceLoginResponse response) {
        return new DeviceLoginResponse(response.getStatus(), response.getVerificationUri(), response.getUserCode(),
                response.getExpiresAt(), response.getIntervalSeconds(), response.getError());
    }

    private static OperationResponse operation(app.alertify.worker.ai.grpc.OperationResponse response) {
        return new OperationResponse(response.getSuccessful(), response.getMessage());
    }

    public record ModuleResponse(boolean workerAvailable, String workerErrorCode, boolean enabled, String authMode,
            String sessionStatus, String accountLabel, String expiresAt, String refreshExpiresAt, String lastRefreshedAt,
            String refreshPolicy, boolean loginPending, String pendingLoginMode, String extensionInstanceId, int extensionProtocolVersion,
            SettingsResponse settings) {
    }

    public record SettingsRequest(boolean enabled, String provider, String model, String reasoningEffort,
            String agentInstructions, String refreshPolicy) {
    }

    public record SettingsResponse(boolean enabled, String provider, String model, String reasoningEffort,
            String agentInstructions, String refreshPolicy) {
    }

    public record BrowserLoginResponse(String mode, String authorizationUrl, String expiresAt, String callbackTicket,
            String callbackUri, String extensionInstanceId, int extensionProtocolVersion) {
    }

    public record DeviceLoginResponse(String status, String verificationUri, String userCode, String expiresAt,
            int intervalSeconds, String error) {
    }

    public record RefreshResponse(boolean successful, String previousExpiresAt, String expiresAt,
            String refreshExpiresAt, String lastRefreshedAt, String message) {
    }

    public record OperationResponse(boolean successful, String message) {
    }
}
