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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import app.alertify.worker.ai.grpc.ChatClientMessage;
import app.alertify.worker.ai.grpc.ChatStart;
import app.alertify.worker.ai.grpc.ChatToolCall;
import app.alertify.worker.ai.grpc.ChatToolResult;
import app.alertify.worker.ai.grpc.ChatWorkerMessage;
import io.grpc.stub.StreamObserver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Provider transport is synthetic; no live requests or credentials are used. */
class CodexChatReceiptTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path directory;

    @Test
    @Timeout(10)
    void successfulReceiptPreservesTheOriginalResultAndAddsTrustedExecutionMetadata() throws Exception {
        Exchange exchange = exchange("Esperando tarjeta siguiente");
        ChatToolCall call = exchange.calls.poll(5, TimeUnit.SECONDS);
        assertThat(call).isNotNull();
        assertThat(exchange.requests).hasSize(1);
        exchange.client.onNext(ChatClientMessage.newBuilder().setToolResult(ChatToolResult.newBuilder()
                .setCallId(call.getCallId()).setOutput("{\"id\":1,\"version\":1,\"description\":\"QA-SYNTHETIC\"}")
                .setOperationMetadataJson("{\"approvalDecision\":\"USER_APPROVED\",\"phase\":\"EXECUTION\",\"status\":\"SUCCEEDED\",\"readOnly\":false,\"fieldComparison\":\"NOT_RECORDED\"}")).build());
        assertThat(exchange.finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exchange.requests).hasSize(2);
        JsonNode next = exchange.requests.get(1);
        JsonNode output = findOutput(next);
        assertThat(output.path("output").stringValue()).contains("QA-SYNTHETIC", "\"version\":1");
        assertThat(output.has("failed")).isFalse();
        assertThat(output.has("diagnosticCode")).isFalse();
        assertThat(output.has("approvalStatus")).isFalse();
        assertThat(exchange.frames.stream().filter(ChatWorkerMessage::hasCompleted).count()).isEqualTo(1);
        assertThat(exchange.calls).isEmpty();
        assertThat(next.path("input").get(0).path("content").get(0).path("text").stringValue())
                .isEqualTo("Audit at start: {\"operations\":[]}. Requested steps: 1 then 2.");
        assertThat(JSON.writeValueAsString(next)).contains("USER_APPROVED", "EXECUTION", "NOT_RECORDED", "diagnosticCode");
        assertThat(JSON.writeValueAsString(next)).contains("already approved", "call the get tool now", "same turn");
        assertThat(output.path("output").stringValue()).isEqualTo("{\"id\":1,\"version\":1,\"description\":\"QA-SYNTHETIC\"}");
    }

    @Test
    @Timeout(10)
    void typedFailureCodeReachesTheModelSeparatelyFromItsSafeText() throws Exception {
        Exchange exchange = exchange("Informo el fallo");
        ChatToolCall call = exchange.calls.poll(5, TimeUnit.SECONDS);
        assertThat(call).isNotNull();
        exchange.client.onNext(ChatClientMessage.newBuilder().setToolResult(ChatToolResult.newBuilder().setCallId(call.getCallId())
                .setFailed(true).setDiagnosticCode("TOOL_RESOURCE_NOT_FOUND")
                .setOutput("Failed: The requested resource ID was not found in this tool's scope.")).build());
        assertThat(exchange.finished.await(5, TimeUnit.SECONDS)).isTrue();
        JsonNode next = exchange.requests.get(1);
        assertThat(findOutput(next).path("output").stringValue()).contains("not found");
        assertThat(JSON.writeValueAsString(next)).contains("TOOL_RESOURCE_NOT_FOUND", "diagnosticCode", "failed");
    }

    @Test
    @Timeout(10)
    void receiptNeverFabricatesApprovalWhenAnOlderBackendSuppliesNoMetadata() throws Exception {
        Exchange exchange = exchange("El cambio se ejecutó; no hice una lectura posterior");
        ChatToolCall call = exchange.calls.poll(5, TimeUnit.SECONDS);
        exchange.client.onNext(ChatClientMessage.newBuilder().setToolResult(ChatToolResult.newBuilder()
                .setCallId(call.getCallId()).setOutput("{\"id\":1,\"version\":1}")).build());
        assertThat(exchange.finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exchange.requests).hasSize(2);
        assertThat(exchange.calls).isEmpty();
        assertThat(exchange.frames.stream().filter(ChatWorkerMessage::hasCompleted).count()).isEqualTo(1);
        assertThat(JSON.writeValueAsString(exchange.requests.get(1))).doesNotContain("USER_APPROVED", "MATCHING_SUCCESSFUL_READ");
    }

    @Test
    @Timeout(10)
    void malformedReceiptPreservesTheOutcomeWithoutEchoingItsBody() throws Exception {
        Exchange exchange = exchange("Metadata no disponible");
        ChatToolCall call = exchange.calls.poll(5, TimeUnit.SECONDS);
        exchange.client.onNext(ChatClientMessage.newBuilder().setToolResult(ChatToolResult.newBuilder()
                .setCallId(call.getCallId()).setOutput("{\"id\":1}")
                .setOperationMetadataJson("PRIVATE-MALFORMED-METADATA")).build());
        assertThat(exchange.finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(findOutput(exchange.requests.get(1)).path("output").stringValue()).isEqualTo("{\"id\":1}");
        assertThat(JSON.writeValueAsString(exchange.requests.get(1))).contains("TOOL_RECEIPT_INVALID").doesNotContain("PRIVATE-MALFORMED");
    }

    private Exchange exchange(String finalText) {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        CodexHttpTransport transport = request -> {
            if (request.uri().getPath().endsWith("/models"))
                return CompletableFuture.completedFuture(new Response(request, 200, "{\"data\":[{\"id\":\"synthetic-model\"}]}"));

            requests.add(JSON.readTree(body(request)));
            Object output = requests.size() == 1
                    ? Map.of("type", "function_call", "call_id", "synthetic-call", "name", "fixture_update", "arguments", "{}")
                    : Map.of("type", "message", "role", "assistant", "content", List.of(Map.of("type", "output_text", "text", finalText)));
            String event = JSON.writeValueAsString(Map.of("type", "response.completed", "response", Map.of("output", List.of(output))));
            return CompletableFuture.completedFuture(new Response(request, 200, "data: " + event + "\n\n"));
        };
        CodexWorkerProperties properties = new CodexWorkerProperties("diagnosis", 0, 1024, Duration.ofSeconds(1), Duration.ofDays(30), 1,
                "synthetic-model", "low", "", "low", "ON_USE", directory.resolve("state.enc"),
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]), null);
        EncryptedStateStore store = new EncryptedStateStore(properties);
        var settings = new AiSettingsState(true, "CODEX", "synthetic-model", "low", "", "ON_USE", "", "low", true);
        var session = new OAuthSession("BROWSER", "synthetic-access", "synthetic-refresh", "synthetic-id", "synthetic-client", "synthetic-subject",
                "", "Synthetic", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), null);
        store.save(new PersistentAiState(settings, session, Map.of(), "synthetic-host"));
        var calls = new LinkedBlockingQueue<ChatToolCall>();
        var frames = new CopyOnWriteArrayList<ChatWorkerMessage>();
        var finished = new CountDownLatch(1);
        var client = new CodexChatService(new CodexSessionService(store, properties, transport)).chat(new StreamObserver<>() {
            @Override
            public void onNext(ChatWorkerMessage frame) {
                frames.add(frame);
                if (frame.hasToolCall())
                    calls.offer(frame.getToolCall());
            }

            @Override
            public void onError(Throwable error) { finished.countDown(); }

            @Override
            public void onCompleted() { finished.countDown(); }
        });
        client.onNext(ChatClientMessage.newBuilder().setStart(ChatStart.newBuilder().setConversationId(1).setTurnId(1)
                .setModel("synthetic-model").setReasoningEffort("low").setMode("DIRECT").setApprovalPolicy("ASK")
                .setInstructions("Audit at start: {\"operations\":[]}. Requested steps: 1 then 2.")).build());
        return new Exchange(client, calls, frames, requests, finished);
    }

    private static JsonNode findOutput(JsonNode request) {
        for (JsonNode item : request.path("input"))
            if ("function_call_output".equals(item.path("type").asString("")))
                return item;

        throw new AssertionError("Missing synthetic function output");
    }

    private static byte[] body(HttpRequest request) {
        var result = new CompletableFuture<byte[]>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }

            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] value = new byte[buffer.remaining()];
                buffer.get(value);
                bytes.writeBytes(value);
            }

            @Override
            public void onError(Throwable error) { result.completeExceptionally(error); }

            @Override
            public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return result.join();
    }

    private record Exchange(StreamObserver<ChatClientMessage> client, LinkedBlockingQueue<ChatToolCall> calls,
            List<ChatWorkerMessage> frames, List<JsonNode> requests, CountDownLatch finished) { }

    private record Response(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }
}
