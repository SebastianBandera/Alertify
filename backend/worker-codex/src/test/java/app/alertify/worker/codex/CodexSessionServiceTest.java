package app.alertify.worker.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexSessionServiceTest {

    @TempDir
    Path directory;

    @Test
    void issuesAnOpaqueOneUseTicketOnlyForExtensionLogins() {
        CodexSessionService service = service();
        assertThat(service.state().settings()).isEqualTo(settings());

        BrowserLoginStart extension = service.beginBrowserLogin(
                "http://127.0.0.1:53682/alertify/api/ai/codex/oauth/callback", "EXTENSION", "admin-subject");

        assertThat(extension.callbackTicket()).isNotBlank();
        assertThat(extension.login().callbackTicketHash()).isNotEqualTo(extension.callbackTicket());
        assertThat(extension.login().adminSubject()).isEqualTo("admin-subject");
        assertThatThrownBy(() -> service.completeBrowserLogin("code", extension.login().state(), "scope", "client", "wrong-ticket"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticket");

        service.cancelLogin();
        BrowserLoginStart direct = service.beginBrowserLogin(
                "http://127.0.0.1:80/alertify/api/ai/codex/oauth/callback", "DIRECT", "admin-subject");
        assertThat(direct.callbackTicket()).isEmpty();
        assertThat(direct.login().callbackTicketHash()).isEmpty();
    }

    @Test
    void rejectsSpoofedLoopbackRedirectsAndExpiredRefreshTokens() {
        CodexWorkerProperties properties = properties(directory.resolve("state.enc"));
        EncryptedStateStore store = new EncryptedStateStore(properties);
        CodexSessionService initialService = new CodexSessionService(store, properties);

        assertThatThrownBy(() -> initialService.beginBrowserLogin(
                "http://127.0.0.1:53682@attacker.example/callback", "EXTENSION", "admin-subject"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("127.0.0.1");

        OAuthSession expired = new OAuthSession("BROWSER", "access", "refresh", "id", "client", "account", "Admin",
                Instant.now().plus(Duration.ofHours(1)), Instant.now().minusSeconds(1), null);
        store.save(new PersistentAiState(settings(), expired, Map.of(), "urn:uuid:test-host"));
        CodexSessionService restored = new CodexSessionService(store, properties);

        assertThat(restored.sessionStatus()).isEqualTo("REAUTH_REQUIRED");
        assertThatThrownBy(restored::testConnection).hasMessageContaining("refresh token expired");
    }

    private CodexSessionService service() {
        CodexWorkerProperties properties = properties(directory.resolve("state.enc"));
        return new CodexSessionService(new EncryptedStateStore(properties), properties);
    }

    private static CodexWorkerProperties properties(Path stateFile) {
        return new CodexWorkerProperties("worker", 0, 1024, Duration.ofSeconds(1), Duration.ofDays(30), "configured-model",
                "low", "ON_USE", stateFile, Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]), null);
    }

    private static AiSettingsState settings() {
        return new AiSettingsState(true, "CODEX", "configured-model", "low", "", "ON_USE");
    }
}
