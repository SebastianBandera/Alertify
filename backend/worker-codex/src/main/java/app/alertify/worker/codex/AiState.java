package app.alertify.worker.codex;

import java.time.Instant;
import java.util.Map;

record AiSettingsState(boolean enabled, String provider, String model, String reasoningEffort, String agentInstructions, String refreshPolicy) {
}

record OAuthSession(String authMode, String accessToken, String refreshToken, String idToken, String clientId, String accountId, String accountLabel, Instant expiresAt, Instant refreshExpiresAt, Instant lastRefreshedAt) {
}

record DynamicClient(String clientId, String accountId, String accountLabel) {
}

record PersistentAiState(AiSettingsState settings, OAuthSession session, Map<String, DynamicClient> dynamicClients, String hostId) {
}

record PendingBrowserLogin(String redirectUri, String loginMode, String adminSubject, String callbackTicketHash, String state, String nonce, String verifier, String clientId, String expectedAccountId, Instant expiresAt) {
}

record BrowserLoginStart(PendingBrowserLogin login, String callbackTicket) {
}

record PendingDeviceLogin(String deviceAuthId, String userCode, Instant expiresAt, int intervalSeconds, String status, String error) {
    PendingDeviceLogin withStatus(String nextStatus, String nextError) {
        return new PendingDeviceLogin(deviceAuthId, userCode, expiresAt, intervalSeconds, nextStatus, nextError);
    }
}
