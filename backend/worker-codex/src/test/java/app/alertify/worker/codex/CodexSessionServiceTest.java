package app.alertify.worker.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

        OAuthSession expired = new OAuthSession("BROWSER", "access", "refresh", "id", "client", "subject", "", "Admin",
                Instant.now().plus(Duration.ofHours(1)), Instant.now().minusSeconds(1), null);
        store.save(new PersistentAiState(settings(), expired, Map.of(), "urn:uuid:test-host"));
        CodexSessionService restored = new CodexSessionService(store, properties);

        assertThat(restored.sessionStatus()).isEqualTo("REAUTH_REQUIRED");
        assertThatThrownBy(restored::testConnection).hasMessageContaining("refresh token expired");
    }

    @Test
    void extractsTheCompletedResponseFromAnSseStream() {
        JsonNode response = CodexSessionService.parseCodexStream(JsonMapper.builder().build(), """
                event: response.created
                data: {"type":"response.created","response":{"id":"pending"}}

                event: response.output_item.done
                data: {"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"CHAT_OK"}]}}

                event: response.completed
                data: {"type":"response.completed","response":{"id":"complete","output":[]}}

                data: [DONE]

                """);

        assertThat(response.path("id").asText()).isEqualTo("complete");
        assertThat(response.path("output").isArray()).isTrue();
        assertThat(response.path("output").get(0).path("content").get(0).path("text").asText()).isEqualTo("CHAT_OK");
    }

    @Test
    void reconstructsTextFromSseDeltasWhenCompletedOutputIsEmpty() {
        JsonNode response = CodexSessionService.parseCodexStream(JsonMapper.builder().build(), """
                data: {"type":"response.output_text.delta","delta":"CHAT_"}

                data: {"type":"response.output_text.delta","delta":"OK"}

                data: {"type":"response.completed","response":{"id":"complete","output":[]}}

                """);

        assertThat(response.path("output").get(0).path("content").get(0).path("text").asText()).isEqualTo("CHAT_OK");
    }

    @Test
    void rejectsFailedAndIncompleteSseStreamsWithoutExposingRemoteDetails() {
        JsonMapper json = JsonMapper.builder().build();

        assertThatThrownBy(() -> CodexSessionService.parseCodexStream(json, """
                data: {"type":"response.failed","response":{"error":{"message":"sensitive remote detail"}}}

                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The Codex response stream reported a failure")
                .hasMessageNotContaining("sensitive");
    }

    @Test
    void migratesTheSubjectAndNamespacedAccountClaimsFromAnExistingSession() {
        CodexWorkerProperties properties = properties(directory.resolve("state.enc"));
        EncryptedStateStore store = new EncryptedStateStore(properties);
        String claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""
                {
                  "sub": "user-subject",
                  "https://api.openai.com/auth": {"chatgpt_account_id": "workspace-account"},
                  "https://api.openai.com/profile": {"email": "admin@example.test"}
                }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String idToken = "header." + claims + ".signature";
        OAuthSession session = new OAuthSession("BROWSER", "access", "refresh", idToken, "client", "", "workspace-account", "Old label",
                Instant.now().plus(Duration.ofHours(1)), Instant.now().plus(Duration.ofDays(1)), null);
        store.save(new PersistentAiState(settings(), session,
                Map.of("http://127.0.0.1/callback", new DynamicClient("client", null, "workspace-account", "Old label")), "urn:uuid:test-host"));

        CodexSessionService restored = new CodexSessionService(store, properties);

        assertThat(restored.state().session().subject()).isEqualTo("user-subject");
        assertThat(restored.state().session().accountId()).isEqualTo("workspace-account");
        assertThat(restored.state().session().accountLabel()).isEqualTo("admin@example.test");
        assertThat(restored.state().dynamicClients().get("http://127.0.0.1/callback").subject()).isEqualTo("user-subject");
        assertThat(restored.state().dynamicClients().get("http://127.0.0.1/callback").accountId()).isEqualTo("workspace-account");
    }

    @Test
    void routesBrowserSessionsThroughPublicResponsesAndDeviceSessionsThroughPrivateCodex() {
        OAuthSession browser = session("BROWSER");
        OAuthSession extension = session("BROWSER_EXTENSION");
        OAuthSession device = session("DEVICE");

        assertThat(CodexSessionService.usesPrivateCodex(browser)).isFalse();
        assertThat(CodexSessionService.usesPrivateCodex(extension)).isFalse();
        assertThat(CodexSessionService.usesPrivateCodex(device)).isTrue();
    }

    @Test
    void parsesThePublicAndPrivateModelCatalogFormats() {
        JsonMapper json = JsonMapper.builder().build();
        Map<String, CodexSessionService.CodexModelProfile> publicModels = CodexSessionService.parseModelCatalog(json.readTree("""
                {"data":[{"id":"gpt-6-luna","object":"model"}]}
                """), false);
        Map<String, CodexSessionService.CodexModelProfile> privateModels = CodexSessionService.parseModelCatalog(json.readTree("""
                {"models":[{"slug":"gpt-6-luna","use_responses_lite":false,"minimal_client_version":"1.2.3",
                  "context_window":272000,"effective_context_window_percent":95}]}
                """), true);

        assertThat(publicModels.get("gpt-6-luna"))
                .isEqualTo(new CodexSessionService.CodexModelProfile(false, true, "0.0.0", false, 0));
        assertThat(privateModels.get("gpt-6-luna"))
                .isEqualTo(new CodexSessionService.CodexModelProfile(false, false, "1.2.3", true, 258400));
    }

    @Test
    void normalizesTokenUsageAndPublicAndCodexRateLimits() {
        JsonNode response = JsonMapper.builder().build().readTree("""
                {"usage":{"input_tokens":120,"input_tokens_details":{"cached_tokens":30,"cache_write_tokens":10},
                  "output_tokens":20,"output_tokens_details":{"reasoning_tokens":8},"total_tokens":140}}
                """);
        HttpHeaders headers = HttpHeaders.of(Map.of(
                "x-ratelimit-limit-requests", List.of("100"),
                "x-ratelimit-remaining-requests", List.of("75"),
                "x-ratelimit-reset-requests", List.of("1m30s"),
                "x-codex-primary-used-percent", List.of("40.5"),
                "x-codex-primary-window-minutes", List.of("300"),
                "x-codex-primary-reset-at", List.of("2000"),
                "x-codex-plan-type", List.of("plus")), (name, value) -> true);

        var usage = CodexSessionService.usage(response, headers, 258400, Instant.ofEpochSecond(1000));

        assertThat(usage.getRequest().getTotalTokens()).isEqualTo(140);
        assertThat(usage.getRequest().getCachedInputTokens()).isEqualTo(30);
        assertThat(usage.getModelContextWindow()).isEqualTo(258400);
        assertThat(usage.getPlanType()).isEqualTo("plus");
        assertThat(usage.getRateLimitsList()).hasSize(2);
        assertThat(usage.getRateLimits(0).getRemainingPercent()).isEqualTo(75);
        assertThat(usage.getRateLimits(0).getResetsAtEpochSeconds()).isEqualTo(1090);
        assertThat(usage.getRateLimits(1).getRemainingPercent()).isEqualTo(59.5);
        assertThat(usage.getRateLimits(1).getWindowMinutes()).isEqualTo(300);
    }

    @Test
    void normalizesCodexUsageSnapshotIncludingTheWeeklyWindow() {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode response = json.readTree("""
                {"usage":{"input_tokens":120,"output_tokens":20,"total_tokens":140}}
                """);
        JsonNode quota = json.readTree("""
                {"plan_type":"plus","rate_limit":{
                  "primary_window":{"used_percent":12,"limit_window_seconds":18000,"reset_after_seconds":120},
                  "secondary_window":{"used_percent":2,"limit_window_seconds":604800,"reset_at":2000}}}
                """);

        var usage = CodexSessionService.usage(response, HttpHeaders.of(Map.of(), (name, value) -> true), quota,
                258400, Instant.ofEpochSecond(1000));

        assertThat(usage.getPlanType()).isEqualTo("plus");
        assertThat(usage.getRateLimitsList()).hasSize(2);
        assertThat(usage.getRateLimits(0).getRemainingPercent()).isEqualTo(88);
        assertThat(usage.getRateLimits(0).getWindowMinutes()).isEqualTo(300);
        assertThat(usage.getRateLimits(0).getResetsAtEpochSeconds()).isEqualTo(1120);
        assertThat(usage.getRateLimits(1).getRemainingPercent()).isEqualTo(98);
        assertThat(usage.getRateLimits(1).getWindowMinutes()).isEqualTo(10080);
        assertThat(usage.getRateLimits(1).getResetsAtEpochSeconds()).isEqualTo(2000);
    }

    @Test
    void usesTheIdTokenSubjectForBrowserLoginWithoutRequiringAnInternalAccountClaim() {
        JsonNode claims = JsonMapper.builder().build().readTree("{\"sub\":\"user-subject\"}");

        assertThat(CodexSessionService.tokenIdentity("BROWSER", claims, true))
                .isEqualTo(new CodexSessionService.TokenIdentity("user-subject", ""));
        assertThat(CodexSessionService.tokenIdentity("BROWSER_EXTENSION", claims, true))
                .isEqualTo(new CodexSessionService.TokenIdentity("user-subject", ""));
        assertThatThrownBy(() -> CodexSessionService.tokenIdentity("DEVICE", claims, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ChatGPT account ID");
    }

    @Test
    void permitsARefreshIdTokenWithoutIdentityClaimsWhileInitialLoginRequiresASubject() {
        JsonNode claims = JsonMapper.builder().build().readTree("{}");

        assertThat(CodexSessionService.tokenIdentity("BROWSER", claims, false))
                .isEqualTo(new CodexSessionService.TokenIdentity("", ""));
        assertThatThrownBy(() -> CodexSessionService.tokenIdentity("BROWSER", claims, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
    }

    private CodexSessionService service() {
        CodexWorkerProperties properties = properties(directory.resolve("state.enc"));
        return new CodexSessionService(new EncryptedStateStore(properties), properties);
    }

    private static CodexWorkerProperties properties(Path stateFile) {
        return new CodexWorkerProperties("worker", 0, 1024, Duration.ofSeconds(1), Duration.ofDays(30), "configured-model",
                "low", "", "low", "ON_USE", stateFile, Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]), null);
    }

    private static AiSettingsState settings() {
        return new AiSettingsState(true, "CODEX", "configured-model", "low", "", "ON_USE", "", "low");
    }

    private static OAuthSession session(String authMode) {
        return new OAuthSession(authMode, "access", "refresh", "id", "client", "subject",
                "DEVICE".equals(authMode) ? "account" : "", "Admin",
                Instant.now().plus(Duration.ofHours(1)), Instant.now().plus(Duration.ofDays(1)), null);
    }
}
