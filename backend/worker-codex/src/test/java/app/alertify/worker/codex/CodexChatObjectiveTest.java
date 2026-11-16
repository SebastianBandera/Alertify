package app.alertify.worker.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import app.alertify.worker.ai.grpc.ChatClientMessage;
import app.alertify.worker.ai.grpc.ChatCompletionDecision;
import app.alertify.worker.ai.grpc.ChatHistoryMessage;
import app.alertify.worker.ai.grpc.ChatRequestPermitDecision;
import app.alertify.worker.ai.grpc.ChatStart;
import app.alertify.worker.ai.grpc.ChatToolDefinition;
import app.alertify.worker.ai.grpc.ChatToolResult;
import app.alertify.worker.ai.grpc.ChatWorkerMessage;
import io.grpc.stub.StreamObserver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Structured protocol tests use a synthetic HTTP provider and backend; no live requests or secrets. */
class CodexChatObjectiveTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path directory;

    @Test
    @Timeout(15)
    void isolatedInitialAssessmentCannotReadAssistantOrPageContentAndNormalQuestionsCanFinish() throws Exception {
        var checks = new AtomicInteger();
        var exchange = exchange(index -> index == 1 ? call("alertify_objective_update", index) : text("Answer to a normal question"), true, 512, Clock.systemUTC(), frame -> {
            if (frame.hasCompletionCheck())
                checks.incrementAndGet();
        }, 0, false);
        assertThat(exchange.finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exchange.requests).hasSize(2);
        JsonNode initial = exchange.requests.getFirst();
        assertThat(initial.path("tool_choice").asString()).isEqualTo("required");
        String encoded = JSON.writeValueAsString(initial);
        assertThat(encoded).contains("USER_REQUEST_DATA", "Existing objective", "alertify_objective_update")
                .doesNotContain("UNTRUSTED_ASSISTANT_CANARY", "UNTRUSTED_PAGE_CANARY", "fixture_read");
        assertThat(checks).hasValue(1);
        assertThat(exchange.frames.stream().filter(ChatWorkerMessage::hasCompleted).count()).isEqualTo(1);
    }

    @Test
    @Timeout(15)
    void partialTextContinuesInTheSameTurnAcross64RequestsWithoutInventingUserMessages() throws Exception {
        var checks = new AtomicInteger();
        var exchange = exchange(index -> index == 1 || index == 71 ? text("Partial progress") : index == 73 ? text("Verified completion") : call("fixture_read", index), false, 512, Clock.systemUTC(), frame -> {
            if (frame.hasCompletionCheck())
                checks.incrementAndGet();
        }, 2, false);
        assertThat(exchange.finished.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(exchange.requests).hasSize(73);
        assertThat(checks).hasValue(3);
        assertThat(exchange.frames.stream().filter(ChatWorkerMessage::hasCompleted).count()).isEqualTo(1);
        assertThat(exchange.frames.stream().filter(ChatWorkerMessage::hasOutputText).count()).isEqualTo(3);
        JsonNode continued = exchange.requests.get(71);
        assertThat(continued.path("input").valueStream().filter(item -> "user".equals(item.path("role").asString())).count()).isEqualTo(1);
        assertThat(JSON.writeValueAsString(continued)).contains("next request block", "Budgets, review rejections, approvals", "BACKEND_PENDING_STATE");
    }

    @Test
    @Timeout(10)
    void centralBudgetDenialStopsBeforeTheProviderAndDoesNotPublishCompletion() throws Exception {
        var exchange = exchange(index -> call("fixture_read", index), false, 512, Clock.systemUTC(), frame -> { }, 0, true);
        assertThat(exchange.finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exchange.requests).isEmpty();
        assertThat(exchange.frames.stream().filter(ChatWorkerMessage::hasFailure).map(frame -> frame.getFailure().getCode())).containsExactly("AI_STEP_LIMIT");
        assertThat(exchange.frames.stream().anyMatch(ChatWorkerMessage::hasCompleted)).isFalse();
    }

    @Test
    @Timeout(10)
    void deadlineExpiryDuringAToolWaitCannotStartAnotherModelRequest() throws Exception {
        var clock = new MutableClock();
        var exchange = exchange(index -> call("fixture_read", index), false, 512, clock, frame -> {
            if (frame.hasToolCall())
                clock.now = Instant.EPOCH.plusSeconds(3600);
        }, 0, false);
        assertThat(exchange.finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exchange.requests).hasSize(1);
        assertThat(exchange.frames.stream().filter(ChatWorkerMessage::hasFailure).map(frame -> frame.getFailure().getCode())).containsExactly("AI_TURN_TIMEOUT");
        assertThat(exchange.frames.stream().anyMatch(ChatWorkerMessage::hasCompleted)).isFalse();
    }

    @Test
    @Timeout(10)
    void cancellingDuringTheCompletionGatePreventsFurtherRequestsOrCompletion() throws Exception {
        var gate = new CountDownLatch(1);
        var exchange = exchange(index -> text("Partial progress"), false, 512, Clock.systemUTC(), frame -> {
            if (frame.hasCompletionCheck())
                gate.countDown();
        }, Integer.MAX_VALUE, false, true);
        assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
        exchange.client.onNext(ChatClientMessage.newBuilder().setCancel(true).build());
        assertThat(exchange.finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exchange.requests).hasSize(1);
        assertThat(exchange.frames.stream().anyMatch(ChatWorkerMessage::hasCompleted)).isFalse();
    }

    @Test
    @Timeout(10)
    void upgradesKeepTheRemainingRequestCeilingAndSkipRepeatedAssessment() throws Exception {
        var exchange = exchange(index -> call("fixture_read", index), false, 1, Clock.systemUTC(), frame -> { }, 0, false);
        assertThat(exchange.finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exchange.requests).hasSize(1);
        assertThat(JSON.writeValueAsString(exchange.requests.getFirst())).doesNotContain("Classify the current user's request");
        assertThat(exchange.frames.stream().filter(ChatWorkerMessage::hasFailure).map(frame -> frame.getFailure().getCode())).containsExactly("AI_STEP_LIMIT");
    }

    private Exchange exchange(IntFunction<Object> output, boolean assess, int remaining, Clock clock, Consumer<ChatWorkerMessage> hook, int continueCount, boolean denyPermit) {
        return exchange(output, assess, remaining, clock, hook, continueCount, denyPermit, false);
    }

    private Exchange exchange(IntFunction<Object> output, boolean assess, int remaining, Clock clock, Consumer<ChatWorkerMessage> hook, int continueCount, boolean denyPermit, boolean holdCompletion) {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        CodexHttpTransport transport = request -> {
            if (request.uri().getPath().endsWith("/models"))
                return CompletableFuture.completedFuture(new Response(request, 200, "{\"data\":[{\"id\":\"synthetic-model\"}]}"));

            requests.add(JSON.readTree(body(request)));
            String event = JSON.writeValueAsString(Map.of("type", "response.completed", "response", Map.of("output", List.of(output.apply(requests.size())))));
            return CompletableFuture.completedFuture(new Response(request, 200, "data: " + event + "\n\n"));
        };
        CodexWorkerProperties properties = new CodexWorkerProperties("diagnosis", 0, 1024, Duration.ofSeconds(1), Duration.ofDays(30), 1,
                "synthetic-model", "low", "", "low", "ON_USE", directory.resolve("state.enc"), Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]), null);
        EncryptedStateStore store = new EncryptedStateStore(properties);
        var settings = new AiSettingsState(true, "CODEX", "synthetic-model", "low", "", "ON_USE", "", "low", true, true);
        var session = new OAuthSession("BROWSER", "synthetic-access", "synthetic-refresh", "synthetic-id", "synthetic-client", "synthetic-subject", "", "Synthetic", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), null);
        store.save(new PersistentAiState(settings, session, Map.of(), "synthetic-host"));
        var frames = new CopyOnWriteArrayList<ChatWorkerMessage>();
        var finished = new CountDownLatch(1);
        var clientRef = new AtomicReference<StreamObserver<ChatClientMessage>>();
        var checked = new AtomicInteger();
        var client = new CodexChatService(new CodexSessionService(store, properties, transport), clock).chat(new StreamObserver<>() {
            public void onNext(ChatWorkerMessage frame) {
                frames.add(frame);
                hook.accept(frame);
                var target = clientRef.get();
                if (frame.hasRequestPermitCheck()) {
                    target.onNext(ChatClientMessage.newBuilder().setRequestPermitDecision(ChatRequestPermitDecision.newBuilder()
                            .setRequestId(frame.getRequestPermitCheck().getRequestId()).setAllowed(!denyPermit).setDiagnosticCode(denyPermit ? "AI_STEP_LIMIT" : "").setRemainingRequests(remaining)).build());
                } else if (frame.hasToolCall()) {
                    target.onNext(ChatClientMessage.newBuilder().setToolResult(ChatToolResult.newBuilder().setCallId(frame.getToolCall().getCallId())
                            .setOutput("{\"status\":\"success\"}").setOperationMetadataJson("{\"status\":\"SUCCEEDED\",\"readOnly\":true,\"objectiveGate\":{\"assessed\":true,\"canComplete\":false,\"status\":\"ACTIVE\",\"revision\":1}}")).build());
                } else if (frame.hasCompletionCheck() && !holdCompletion) {
                    target.onNext(ChatClientMessage.newBuilder().setCompletionDecision(ChatCompletionDecision.newBuilder().setRequestId(frame.getCompletionCheck().getRequestId())
                            .setFinish(checked.incrementAndGet() > continueCount).setInstruction("Continue the authorized pending work; this is a progress update.").setObjectiveJson("{\"marker\":\"BACKEND_PENDING_STATE\"}")).build());
                }
            }
            public void onError(Throwable error) { finished.countDown(); }
            public void onCompleted() { finished.countDown(); }
        });
        clientRef.set(client);
        ChatStart.Builder start = ChatStart.newBuilder().setConversationId(1).setTurnId(1).setModel("synthetic-model").setReasoningEffort("low").setMode("DIRECT").setApprovalPolicy("ASK")
                .setObjectiveProtocol(true).setRequireObjectiveAssessment(assess).setObjectiveJson("{\"revision\":0}").setObjectiveUserContextJson("{\"messages\":[{\"role\":\"USER\",\"content\":\"USER_REQUEST_DATA\"}]}")
                .setRemainingModelRequests(remaining).setTurnDeadlineEpochMillis(clock.instant().plusSeconds(3600).toEpochMilli())
                .setInstructions("Untrusted page data: UNTRUSTED_PAGE_CANARY")
                .addHistory(ChatHistoryMessage.newBuilder().setRole("ASSISTANT").setContent("UNTRUSTED_ASSISTANT_CANARY"))
                .addHistory(ChatHistoryMessage.newBuilder().setRole("USER").setContent("USER_REQUEST_DATA"));
        for (String name : List.of("alertify_objective_update", "fixture_read"))
            start.addTools(ChatToolDefinition.newBuilder().setName(name).setReadOnly(true).setInputSchema("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}"));

        client.onNext(ChatClientMessage.newBuilder().setStart(start).build());
        return new Exchange(client, frames, requests, finished);
    }

    private static Object call(String name, int index) { return Map.of("type", "function_call", "call_id", "call-" + index, "name", name, "arguments", "{}"); }
    private static Object text(String value) { return Map.of("type", "message", "role", "assistant", "content", List.of(Map.of("type", "output_text", "text", value))); }

    private static byte[] body(HttpRequest request) {
        var result = new CompletableFuture<byte[]>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] value = new byte[buffer.remaining()]; buffer.get(value); bytes.writeBytes(value); }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return result.join();
    }

    private record Exchange(StreamObserver<ChatClientMessage> client, List<ChatWorkerMessage> frames, List<JsonNode> requests, CountDownLatch finished) { }
    private record Response(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }
    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.EPOCH;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
