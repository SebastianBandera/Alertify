package app.alertify.worker.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CodexSessionServiceTest {

    @TempDir
    Path directory;

    @Test
    void rejectsMissingEmptyAndWrongAudienceAndAcceptsTheClientAudience() {
        CodexSessionService service = service();
        JsonNode token = JsonMapper.builder().build().valueToTree(Map.of("access_token", "test-access", "id_token", "test-id"));
        for (List<String> audience : List.of(List.<String>of(), List.of("other-client"))) {
            Jwt jwt = Jwt.withTokenValue("test-id").header("alg", "none").subject("test-subject").audience(audience).build();
            ReflectionTestUtils.setField(service, "jwtDecoder", (JwtDecoder) value -> jwt);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "tokenSession", "BROWSER", token, "client", null, true))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("The ID token audience did not match this client");
        }
        Jwt missing = Jwt.withTokenValue("test-id").header("alg", "none").subject("test-subject").build();
        ReflectionTestUtils.setField(service, "jwtDecoder", (JwtDecoder) value -> missing);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "tokenSession", "BROWSER", token, "client", null, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The ID token audience did not match this client");
        Jwt valid = Jwt.withTokenValue("test-id").header("alg", "none").subject("test-subject").audience(List.of("other-client", "client")).build();
        ReflectionTestUtils.setField(service, "jwtDecoder", (JwtDecoder) value -> valid);
        OAuthSession session = ReflectionTestUtils.invokeMethod(service, "tokenSession", "BROWSER", token, "client", null, true);
        assertThat(session.subject()).isEqualTo("test-subject");
    }

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

    @Test
    @Timeout(10)
    void limitsConcurrentModelRequestsAndReleasesTheNextQueuedRequest() throws Exception {
        ControlledTransport transport = new ControlledTransport();
        CodexSessionService service = authenticatedService(transport);
        List<CodexSessionService.ProviderCall> calls = List.of(
                service.codexRequest(Map.of(), metadata(1), 1L, 1L),
                service.codexRequest(Map.of(), metadata(2), 2L, 2L),
                service.codexRequest(Map.of(), metadata(3), 3L, 3L),
                service.codexRequest(Map.of(), metadata(4), 4L, 4L));
        List<CompletableFuture<CodexSessionService.CodexResponse>> results = calls.stream().map(CodexSessionServiceTest::execute).toList();

        assertThat(transport.awaitStarted(3)).isTrue();
        assertThat(transport.modelRequestCount()).isEqualTo(3);
        transport.completeOne();
        assertThat(transport.awaitStarted(1)).isTrue();
        assertThat(transport.modelRequestCount()).isEqualTo(4);
        transport.completeAll();

        results.forEach(result -> assertThat(result.join().body().path("id").stringValue()).isEqualTo("complete"));
    }

    @Test
    @Timeout(10)
    void queuedRequestUsesTheSessionRefreshedWhileWaitingForAPermit() throws Exception {
        ControlledTransport transport = new ControlledTransport();
        CodexSessionService service = authenticatedService(transport, "BROWSER", 1);
        CodexSessionService.ProviderCall firstCall = service.codexRequest(Map.of(), metadata(1), 1L, 1L);
        CodexSessionService.ProviderCall queuedCall = service.codexRequest(Map.of(), metadata(2), 2L, 2L);
        CompletableFuture<CodexSessionService.CodexResponse> firstResult = execute(firstCall);
        assertThat(transport.awaitStarted(1)).isTrue();
        CompletableFuture<CodexSessionService.CodexResponse> queuedResult = execute(queuedCall);

        service.refresh();
        transport.completeOne();
        assertThat(transport.awaitStarted(1)).isTrue();
        transport.completeAll();

        assertThat(firstResult.join().body().path("id").stringValue()).isEqualTo("complete");
        assertThat(queuedResult.join().body().path("id").stringValue()).isEqualTo("complete");
        assertThat(transport.modelAuthorizations()).containsExactly("Bearer access", "Bearer refreshed-access");
    }

    @Test
    @Timeout(10)
    void usageRequestUsesTheSessionRefreshedDuringModelGeneration() throws Exception {
        ControlledTransport transport = new ControlledTransport();
        CodexSessionService service = authenticatedService(transport, "DEVICE", 1);
        CodexSessionService.ProviderCall call = service.codexRequest(Map.of(), metadata(1, true), 1L, 1L);
        CompletableFuture<CodexSessionService.CodexResponse> result = execute(call);
        assertThat(transport.awaitStarted(1)).isTrue();

        service.refresh();
        transport.completeOne();

        assertThat(result.join().body().path("id").stringValue()).isEqualTo("complete");
        assertThat(transport.usageAuthorizations()).containsExactly("Bearer refreshed-access");
    }

    @Test
    @Timeout(10)
    void logoutCancelsTheActiveHttpRequestAndRejectsItsLateResult() throws Exception {
        ControlledTransport transport = new ControlledTransport();
        CodexSessionService service = authenticatedService(transport);
        CodexSessionService.ProviderCall call = service.codexRequest(Map.of(), metadata(1), 1L, 1L);
        CompletableFuture<CodexSessionService.CodexResponse> result = execute(call);
        assertThat(transport.awaitStarted(1)).isTrue();

        assertThat(service.logout()).isTrue();

        assertThatThrownBy(result::join).hasRootCauseInstanceOf(CodexSessionService.SessionChangedException.class);
        assertThat(service.sessionStatus()).isEqualTo("SIGNED_OUT");
        assertThat(transport.cancelledModelRequests()).isEqualTo(1);
    }

    @Test
    void logoutBeforeExecutionRejectsTheRequestWithoutSendingIt() {
        ControlledTransport transport = new ControlledTransport();
        CodexSessionService service = authenticatedService(transport);
        CodexSessionService.ProviderCall call = service.codexRequest(Map.of(), metadata(1), 1L, 1L);

        assertThat(service.logout()).isTrue();

        assertThatThrownBy(call::execute).isInstanceOf(CodexSessionService.SessionChangedException.class);
        assertThat(transport.modelRequestCount()).isZero();
    }

    @Test
    @Timeout(10)
    void settingsRemainWritableWhileAModelRequestIsPending() throws Exception {
        ControlledTransport transport = new ControlledTransport();
        CodexSessionService service = authenticatedService(transport);
        CodexSessionService.ProviderCall call = service.codexRequest(Map.of(), metadata(1), 1L, 1L);
        CompletableFuture<CodexSessionService.CodexResponse> result = execute(call);
        assertThat(transport.awaitStarted(1)).isTrue();

        AiSettingsState updated = new AiSettingsState(true, "CODEX", "other-model", "low", "instructions", "ON_USE", "", "low");
        assertThat(service.updateSettings(updated)).isEqualTo(updated);

        call.cancel(CodexSessionService.CancellationReason.USER);
        assertThatThrownBy(result::join).hasCauseInstanceOf(CodexSessionService.ProviderRequestCancelledException.class);
    }

    private CodexSessionService service() {
        CodexWorkerProperties properties = properties(directory.resolve("state.enc"));
        return new CodexSessionService(new EncryptedStateStore(properties), properties);
    }

    private CodexSessionService authenticatedService(CodexHttpTransport transport) {
        return authenticatedService(transport, "BROWSER", 3);
    }

    private CodexSessionService authenticatedService(CodexHttpTransport transport, String authMode, int maxConcurrentModelRequests) {
        CodexWorkerProperties properties = properties(directory.resolve("authenticated-state.enc"), maxConcurrentModelRequests);
        EncryptedStateStore store = new EncryptedStateStore(properties);
        store.save(new PersistentAiState(settings(), session(authMode), Map.of(), "urn:uuid:test-host"));
        return new CodexSessionService(store, properties, transport);
    }

    private static CodexSessionService.CodexRequestMetadata metadata(long turnId) {
        return metadata(turnId, false);
    }

    private static CodexSessionService.CodexRequestMetadata metadata(long turnId, boolean privateCodex) {
        return new CodexSessionService.CodexRequestMetadata("configured-model", false, false, "0.0.0", privateCodex, 272000,
                "installation", "session", "thread", "window", Long.toString(turnId), 0);
    }

    private static CompletableFuture<CodexSessionService.CodexResponse> execute(CodexSessionService.ProviderCall call) {
        CompletableFuture<CodexSessionService.CodexResponse> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(call.execute());
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    private static CodexWorkerProperties properties(Path stateFile) {
        return properties(stateFile, 3);
    }

    private static CodexWorkerProperties properties(Path stateFile, int maxConcurrentModelRequests) {
        return new CodexWorkerProperties("worker", 0, 1024, Duration.ofSeconds(1), Duration.ofDays(30), maxConcurrentModelRequests, "configured-model",
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

    private static final class ControlledTransport implements CodexHttpTransport {
        private static final String RESPONSE = """
                data: {"type":"response.completed","response":{"id":"complete","output":[],"usage":{"total_tokens":0}}}

                """;

        private final AtomicInteger modelRequestCount = new AtomicInteger();
        private final AtomicInteger cancelledModelRequests = new AtomicInteger();
        private final Semaphore started = new Semaphore(0);
        private final Queue<CompletableFuture<HttpResponse<String>>> pending = new ConcurrentLinkedQueue<>();
        private final Queue<String> modelAuthorizations = new ConcurrentLinkedQueue<>();
        private final Queue<String> usageAuthorizations = new ConcurrentLinkedQueue<>();

        @Override
        public CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest request) {
            String path = request.uri().getPath();
            if (path.endsWith("/oauth/token"))
                return CompletableFuture.completedFuture(new StubResponse(request, 200,
                        "{\"access_token\":\"refreshed-access\",\"expires_in\":3600}"));
            if (path.endsWith("/wham/usage")) {
                usageAuthorizations.add(authorization(request));
                return CompletableFuture.completedFuture(new StubResponse(request, 200, "{}"));
            }
            if (!path.endsWith("/responses"))
                return CompletableFuture.completedFuture(new StubResponse(request, 200, "{}"));

            modelRequestCount.incrementAndGet();
            modelAuthorizations.add(authorization(request));
            CompletableFuture<HttpResponse<String>> response = new CompletableFuture<>();
            response.whenComplete((ignored, error) -> {
                if (response.isCancelled()) cancelledModelRequests.incrementAndGet();
            });
            pending.add(response);
            started.release();
            return response;
        }

        boolean awaitStarted(int count) throws InterruptedException {
            return started.tryAcquire(count, 5, TimeUnit.SECONDS);
        }

        int modelRequestCount() {
            return modelRequestCount.get();
        }

        int cancelledModelRequests() {
            return cancelledModelRequests.get();
        }

        List<String> modelAuthorizations() {
            return List.copyOf(modelAuthorizations);
        }

        List<String> usageAuthorizations() {
            return List.copyOf(usageAuthorizations);
        }

        void completeOne() {
            pending.remove().complete(new StubResponse(null, 200, RESPONSE));
        }

        void completeAll() {
            CompletableFuture<HttpResponse<String>> response;
            while ((response = pending.poll()) != null)
                response.complete(new StubResponse(null, 200, RESPONSE));
        }

        private static String authorization(HttpRequest request) {
            return request.headers().firstValue("Authorization").orElse("");
        }
    }

    private record StubResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override
        public URI uri() { return request == null ? URI.create("https://example.test") : request.uri(); }
        @Override
        public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        @Override
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }
}
