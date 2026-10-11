package app.alertify.worker.codex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import app.alertify.worker.ai.grpc.ChatClientMessage;
import app.alertify.worker.ai.grpc.ChatCompleted;
import app.alertify.worker.ai.grpc.ChatFailure;
import app.alertify.worker.ai.grpc.ChatStart;
import app.alertify.worker.ai.grpc.ChatToolCall;
import app.alertify.worker.ai.grpc.ChatToolDefinition;
import app.alertify.worker.ai.grpc.ChatToolResult;
import app.alertify.worker.ai.grpc.ChatWorkerMessage;
import app.alertify.worker.ai.grpc.ReviewToolCallRequest;
import app.alertify.worker.ai.grpc.ReviewToolCallResponse;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class CodexChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodexChatService.class);
    private static final String REVIEW_INSTRUCTIONS = "You are a strict safety reviewer for an Alertify administrator. "
            + "Decide whether the proposed tool call is necessary, scoped to the request, and safe enough to execute. "
            + "Reply with exactly ALLOW or REJECT followed by one short reason. Never output anything else.";
    private static final int MAX_STEPS = 64;

    private final CodexSessionService sessions;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    CodexChatService(CodexSessionService sessions) {
        this.sessions = sessions;
    }

    StreamObserver<ChatClientMessage> chat(StreamObserver<ChatWorkerMessage> observer) {
        ChatExchange exchange = new ChatExchange(observer);
        return new StreamObserver<>() {
            @Override
            public void onNext(ChatClientMessage message) {
                exchange.accept(message);
            }

            @Override
            public void onError(Throwable throwable) {
                exchange.transportFailed(throwable);
            }

            @Override
            public void onCompleted() {
                exchange.clientCompleted();
            }
        };
    }

    ReviewToolCallResponse review(ReviewToolCallRequest request) {
        long startedAt = System.nanoTime();
        LOGGER.info("event=AI_TOOL_REVIEW outcome=STARTED conversationId={} turnId={} tool={} model={} reasoningEffort={} argumentsBytes={}", request.getConversationId(), request.getTurnId(), request.getToolName(), request.getModel(), request.getReasoningEffort(), byteLength(request.getArguments()));
        try {
            String prompt = "User request:\n" + request.getUserRequest() + "\n\nTool: " + request.getToolName()
                    + "\nDescription: " + request.getToolDescription() + "\nArguments: " + request.getArguments()
                    + "\nHints: destructive=" + request.getDestructiveHint() + ", idempotent=" + request.getIdempotentHint()
                    + ", openWorld=" + request.getOpenWorldHint();
            List<Object> input = new ArrayList<>();
            input.add(Map.of("role", "user", "content", prompt));
            CodexSessionService.CodexRequestMetadata metadata = sessions.requestMetadata(request.getModel(), null, null);
            CodexSessionService.ProviderCall call = sessions.codexRequest(requestBody(request.getModel(), request.getReasoningEffort(),
                    REVIEW_INSTRUCTIONS, input, List.of(), metadata.responsesLite(), metadata.namespacedTools()), metadata,
                    request.getConversationId(), request.getTurnId());
            Context context = Context.current();
            Context.CancellationListener cancellationListener = ignored -> call.cancel(CodexSessionService.CancellationReason.USER);
            context.addListener(cancellationListener, Runnable::run);
            CodexSessionService.CodexResponse response;
            try {
                response = call.execute();
            } finally {
                context.removeListener(cancellationListener);
            }
            sessions.validateSession(metadata);
            String answer = outputText(response.body()).trim();
            boolean allowed = answer.toUpperCase(Locale.ROOT).startsWith("ALLOW");
            String reason = answer.replaceFirst("(?i)^(ALLOW|REJECT)\\s*[:.-]?\\s*", "").trim();
            LOGGER.info("event=AI_TOOL_REVIEW outcome={} conversationId={} turnId={} tool={} durationMs={} totalTokens={}", allowed ? "ALLOWED" : "REJECTED", request.getConversationId(), request.getTurnId(), request.getToolName(), elapsedMillis(startedAt), response.usage().getRequest().getTotalTokens());
            return ReviewToolCallResponse.newBuilder().setAllowed(allowed)
                    .setReason(reason.isBlank() ? (allowed ? "Approved" : "Rejected") : reason).setUsage(response.usage()).build();
        } catch (RuntimeException exception) {
            LOGGER.error("event=AI_TOOL_REVIEW outcome=FAILED conversationId={} turnId={} tool={} durationMs={} errorType={} detail={}", request.getConversationId(), request.getTurnId(), request.getToolName(), elapsedMillis(startedAt), exception.getClass().getSimpleName(), safeMessage(exception));
            throw exception;
        }
    }

    private final class ChatExchange {
        private final StreamObserver<ChatWorkerMessage> observer;
        private final BlockingQueue<ChatToolResult> results = new LinkedBlockingQueue<>();
        private volatile ChatStart start;
        private volatile String phase = "AWAITING_START";
        private volatile long startedAt;
        private volatile boolean cancelled;
        private volatile boolean started;
        private volatile Thread runner;
        private volatile CodexSessionService.ProviderCall activeCall;

        private ChatExchange(StreamObserver<ChatWorkerMessage> observer) {
            this.observer = observer;
        }

        private void accept(ChatClientMessage message) {
            switch (message.getPayloadCase()) {
                case START -> start(message.getStart());
                case TOOL_RESULT -> results.offer(message.getToolResult());
                case CANCEL -> cancel();
                case PAYLOAD_NOT_SET -> fail("AI_PROTOCOL_INVALID", "The chat frame did not contain a payload");
            }
        }

        private synchronized void start(ChatStart start) {
            if (started) {
                fail("AI_PROTOCOL_INVALID", "The chat exchange was already started");
                return;
            }
            started = true;
            this.start = start;
            phase = "PREPARING";
            startedAt = System.nanoTime();
            LOGGER.info("event=AI_CHAT_TURN outcome=STARTED conversationId={} turnId={} mode={} approvalPolicy={} model={} reasoningEffort={} historyMessages={} initialTools={}", start.getConversationId(), start.getTurnId(), start.getMode(), start.getApprovalPolicy(), start.getModel(), start.getReasoningEffort(), start.getHistoryCount(), start.getToolsCount());
            runner = Thread.ofVirtual().name("codex-chat-" + start.getTurnId()).start(() -> run(start));
        }

        private void run(ChatStart start) {
            int modelRequests = 0;
            int toolCalls = 0;
            try {
                List<Object> input = new ArrayList<>();
                start.getHistoryList().forEach(message -> input.add(Map.of("role", message.getRole().toLowerCase(Locale.ROOT), "content", message.getContent())));
                List<Object> tools = new ArrayList<>(start.getToolsList().stream().map(value -> (Object) tool(value)).toList());
                CodexSessionService.CodexRequestMetadata metadata = sessions.requestMetadata(start.getModel(), start.getConversationId(), start.getTurnId());
                int emptyResponses = 0;
                for (int step = 0; step < MAX_STEPS && !cancelled; step++) {
                    phase = "MODEL_REQUEST";
                    long requestStartedAt = System.nanoTime();
                    modelRequests++;
                    LOGGER.info("event=AI_MODEL_REQUEST outcome=STARTED conversationId={} turnId={} step={} model={} reasoningEffort={} inputItems={} availableTools={} transport={}", start.getConversationId(), start.getTurnId(), step + 1, start.getModel(), start.getReasoningEffort(), input.size(), tools.size(), metadata.privateCodex() ? "PRIVATE_CODEX" : "PUBLIC_RESPONSES");
                    CodexSessionService.ProviderCall providerCall = sessions.codexRequest(requestBody(start.getModel(), start.getReasoningEffort(), start.getInstructions(), input, tools, metadata.responsesLite(), metadata.namespacedTools()), metadata, start.getConversationId(), start.getTurnId());
                    activeCall = providerCall;
                    CodexSessionService.CodexResponse codexResponse;
                    try {
                        codexResponse = providerCall.execute();
                    } finally {
                        activeCall = null;
                    }
                    if (cancelled)
                        break;

                    sessions.validateSession(metadata);
                    observer.onNext(ChatWorkerMessage.newBuilder().setUsage(codexResponse.usage()).build());
                    JsonNode response = codexResponse.body();
                    JsonNode output = response.path("output");

                    if (!output.isArray())
                        throw new IllegalStateException("The Codex response did not contain an output array");

                    phase = "PROCESSING_RESPONSE";
                    LOGGER.info("event=AI_MODEL_REQUEST outcome=COMPLETED conversationId={} turnId={} step={} durationMs={} totalTokens={} outputShape={}", start.getConversationId(), start.getTurnId(), step + 1, elapsedMillis(requestStartedAt), codexResponse.usage().getRequest().getTotalTokens(), outputShape(output));

                    output.forEach(item -> input.add(toValue(item)));
                    JsonNode call = firstFunctionCall(output);
                    String text = outputText(response).strip();
                    if (call == null) {
                        if (text.isBlank() && emptyResponses++ == 0)
                            continue;
                        if (text.isBlank())
                            throw new ProviderProtocolException("The Codex response did not contain a message or function call (output types=" + outputShape(output) + ")");

                        observer.onNext(ChatWorkerMessage.newBuilder().setOutputText(text).build());
                        observer.onNext(ChatWorkerMessage.newBuilder().setCompleted(ChatCompleted.newBuilder()
                                .setModel(start.getModel()).setReasoningEffort(start.getReasoningEffort()).build()).build());
                        phase = "COMPLETED";
                        observer.onCompleted();
                        LOGGER.info("event=AI_CHAT_TURN outcome=COMPLETED conversationId={} turnId={} durationMs={} modelRequests={} toolCalls={} responseBytes={}", start.getConversationId(), start.getTurnId(), elapsedMillis(startedAt), modelRequests, toolCalls, byteLength(text));
                        return;
                    }

                    if (!text.isBlank())
                        observer.onNext(ChatWorkerMessage.newBuilder().setOutputText(text).build());

                    String callId = text(call.path("call_id"), text(call.path("id"), ""));
                    String name = text(call.path("name"), "");
                    String arguments = text(call.path("arguments"), "{}");
                    if (callId.isBlank() || name.isBlank())
                        throw new ProviderProtocolException("The Codex tool call was incomplete (callIdPresent=" + !callId.isBlank() + ", namePresent=" + !name.isBlank() + ")");

                    emptyResponses = 0;
                    toolCalls++;
                    LOGGER.info("event=AI_TOOL_CALL outcome=REQUESTED conversationId={} turnId={} step={} tool={} callId={} argumentsBytes={}", start.getConversationId(), start.getTurnId(), step + 1, name, callId, byteLength(arguments));
                    observer.onNext(ChatWorkerMessage.newBuilder().setToolCall(ChatToolCall.newBuilder().setCallId(callId).setName(name).setArguments(arguments).build()).build());
                    phase = "WAITING_TOOL_RESULT";
                    ChatToolResult result = results.take();
                    if (!callId.equals(result.getCallId()))
                        throw new IllegalStateException("The tool result did not match the pending call");

                    phase = "PROCESSING_TOOL_RESULT";
                    LOGGER.info("event=AI_TOOL_CALL outcome={} conversationId={} turnId={} step={} tool={} callId={} diagnosticCode={} resultBytes={} additionalTools={}", result.getFailed() ? "FAILED" : "COMPLETED", start.getConversationId(), start.getTurnId(), step + 1, name, callId, result.getDiagnosticCode(), byteLength(result.getOutput()), result.getAdditionalToolsCount());
                    result.getAdditionalToolsList().forEach(definition -> tools.add(tool(definition)));
                    input.add(Map.of("type", "function_call_output", "call_id", callId, "output", result.getOutput()));
                }
                if (cancelled) {
                    observer.onCompleted();
                    LOGGER.info("event=AI_CHAT_TURN outcome=CANCELLED conversationId={} turnId={} durationMs={} modelRequests={} toolCalls={}", start.getConversationId(), start.getTurnId(), elapsedMillis(startedAt), modelRequests, toolCalls);
                    return;
                }
                fail("AI_STEP_LIMIT", "The model exceeded the maximum number of tool steps");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!cancelled)
                    fail("AI_CHAT_INTERRUPTED", "The chat exchange was interrupted");
            } catch (CodexSessionService.SessionChangedException exception) {
                fail("AI_SESSION_CHANGED", exception.getMessage());
            } catch (CodexSessionService.ProviderRequestCancelledException exception) {
                if (!cancelled)
                    fail("AI_PROVIDER_CANCELLED", exception.getMessage());
            } catch (RuntimeException exception) {
                fail("AI_PROVIDER_FAILED", safeMessage(exception));
            } finally {
                runner = null;
                activeCall = null;
            }
        }

        private void clientCompleted() {
            if (!started) {
                observer.onCompleted();
                return;
            }

            cancel();
        }

        private void cancel() {
            if (cancelled)
                return;

            cancelled = true;
            ChatStart current = start;
            LOGGER.info("event=AI_CHAT_TURN outcome=CANCEL_REQUESTED conversationId={} turnId={} phase={} durationMs={}", current == null ? 0 : current.getConversationId(), current == null ? 0 : current.getTurnId(), phase, startedAt == 0 ? 0 : elapsedMillis(startedAt));
            CodexSessionService.ProviderCall currentCall = activeCall;
            if (currentCall != null)
                currentCall.cancel(CodexSessionService.CancellationReason.USER);
            Thread currentRunner = runner;
            if (currentRunner != null)
                currentRunner.interrupt();
            results.offer(ChatToolResult.newBuilder().setCallId("").setFailed(true).setOutput("Cancelled").build());
        }

        private void transportFailed(Throwable throwable) {
            ChatStart current = start;
            LOGGER.warn("event=AI_CHAT_STREAM outcome=FAILED conversationId={} turnId={} errorType={}", current == null ? 0 : current.getConversationId(), current == null ? 0 : current.getTurnId(), throwable.getClass().getSimpleName());
            cancel();
        }

        private void fail(String code, String message) {
            if (cancelled)
                return;
            cancelled = true;
            ChatStart current = start;
            LOGGER.error("event=AI_CHAT_TURN outcome=FAILED conversationId={} turnId={} phase={} durationMs={} code={} detail={}", current == null ? 0 : current.getConversationId(), current == null ? 0 : current.getTurnId(), phase, startedAt == 0 ? 0 : elapsedMillis(startedAt), code, message);
            observer.onNext(ChatWorkerMessage.newBuilder().setFailure(ChatFailure.newBuilder()
                    .setCode(code).setMessage(message).build()).build());
            observer.onCompleted();
        }
    }

    private Map<String, Object> tool(ChatToolDefinition definition) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "function");
        value.put("name", definition.getName());
        value.put("description", definition.getDescription());
        value.put("parameters", strictSchema(json.readValue(definition.getInputSchema(), Object.class)));
        value.put("strict", true);
        return value;
    }

    @SuppressWarnings("unchecked")
    static Object strictSchema(Object value) {
        if (value instanceof List<?> list) {
            list.forEach(CodexChatService::strictSchema);
            return value;
        }
        if (!(value instanceof Map<?, ?> source))
            return value;

        Map<String, Object> schema = (Map<String, Object>) source;
        schema.values().forEach(CodexChatService::strictSchema);
        if (!"object".equals(schema.get("type")) || !(schema.get("properties") instanceof Map<?, ?> rawProperties))
            return value;

        Map<String, Object> properties = (Map<String, Object>) rawProperties;
        Set<String> alreadyRequired = schema.get("required") instanceof List<?> required
                ? required.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet()) : Set.of();
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            strictSchema(property.getValue());
            if (!alreadyRequired.contains(property.getKey()))
                property.setValue(Map.of("anyOf", List.of(property.getValue(), Map.of("type", "null"))));
        }
        schema.put("required", new ArrayList<>(properties.keySet()));
        schema.put("additionalProperties", false);
        return value;
    }

    static Map<String, Object> requestBody(String model, String effort, String instructions, List<Object> input, List<Object> tools, boolean responsesLite, boolean namespacedTools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Object> requestInput = new ArrayList<>();
        if (responsesLite && !tools.isEmpty())
            requestInput.add(Map.of("type", "additional_tools", "role", "developer", "tools", List.of(Map.of(
                    "type", "namespace", "name", "functions", "description", "Alertify tools", "tools", tools))));
        if (!instructions.isBlank())
            requestInput.add(Map.of("type", "message", "role", "developer", "content",
                    List.of(Map.of("type", "input_text", "text", instructions))));
        requestInput.addAll(input);
        body.put("input", requestInput);
        if (!responsesLite && !tools.isEmpty())
            body.put("tools", namespacedTools ? List.of(Map.of("type", "namespace", "name", "functions",
                    "description", "Alertify tools", "tools", tools)) : tools);
        body.put("tool_choice", "auto");
        body.put("parallel_tool_calls", false);
        Map<String, Object> reasoning = new LinkedHashMap<>();
        reasoning.put("effort", effort);
        reasoning.put("summary", "auto");
        if (responsesLite)
            reasoning.put("context", "all_turns");
        body.put("reasoning", reasoning);
        body.put("include", List.of("reasoning.encrypted_content"));
        body.put("store", false);
        body.put("stream", true);
        return body;
    }

    private Object toValue(JsonNode node) {
        return json.readValue(json.writeValueAsString(node), Object.class);
    }

    private static JsonNode firstFunctionCall(JsonNode output) {
        for (JsonNode item : output)
            if ("function_call".equals(text(item.path("type"), "")))
                return item;
        return null;
    }

    static String outputText(JsonNode response) {
        String directOutput = text(response.path("output_text"), "");
        if (!directOutput.isBlank())
            return directOutput;

        StringBuilder output = new StringBuilder();
        for (JsonNode item : response.path("output")) {
            if (!"message".equals(text(item.path("type"), "")))
                continue;
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(text(content.path("type"), "")))
                    output.append(text(content.path("text"), ""));
                else if ("refusal".equals(text(content.path("type"), "")))
                    output.append(text(content.path("refusal"), ""));
            }
        }
        return output.toString();
    }

    static String outputShape(JsonNode output) {
        List<String> types = new ArrayList<>();
        for (JsonNode item : output) {
            String type = text(item.path("type"), "unknown");
            if ("message".equals(type) && item.path("content").isArray()) {
                List<String> contentTypes = new ArrayList<>();
                item.path("content").forEach(content -> contentTypes.add(text(content.path("type"), "unknown")));
                type += contentTypes;
            }
            types.add(type);
        }
        return types.isEmpty() ? "empty" : String.join(",", types);
    }

    private static String text(JsonNode node, String fallback) {
        return node.isString() ? node.stringValue() : fallback;
    }

    private static String safeMessage(RuntimeException exception) {
        if (exception instanceof CodexSessionService.RemoteServiceException || exception instanceof ProviderProtocolException)
            return exception.getMessage();

        return "The Codex request failed (" + exception.getClass().getSimpleName() + ")";
    }

    private static int byteLength(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private static final class ProviderProtocolException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private ProviderProtocolException(String message) {
            super(message);
        }
    }
}
