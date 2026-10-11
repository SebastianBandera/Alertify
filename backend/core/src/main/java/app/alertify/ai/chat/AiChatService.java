package app.alertify.ai.chat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import app.alertify.ai.AiConversationContext;
import app.alertify.ai.AiRefreshTarget;
import app.alertify.ai.AiToolCatalog;
import app.alertify.ai.AiToolDescriptor;
import app.alertify.ai.AiToolGroup;
import app.alertify.ai.localization.AiLocalizationSourceService;
import app.alertify.ai.tools.LocalizationAiTools;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.codex.AiWorkerClient;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.realtime.AdminEventPublisher;
import app.alertify.worker.ai.grpc.AiSettings;
import app.alertify.worker.ai.grpc.ChatClientMessage;
import app.alertify.worker.ai.grpc.ChatHistoryMessage;
import app.alertify.worker.ai.grpc.ChatStart;
import app.alertify.worker.ai.grpc.ChatToolDefinition;
import app.alertify.worker.ai.grpc.ChatToolResult;
import app.alertify.worker.ai.grpc.ChatWorkerMessage;
import app.alertify.worker.ai.grpc.ReviewToolCallRequest;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AiChatService {

    private static final int MAX_MESSAGE_BYTES = 32 * 1024;
    private static final int MAX_TOOL_ARGUMENT_BYTES = 128 * 1024;
    private static final int MAX_TOOL_OUTPUT_BYTES = 128 * 1024;
    private static final int MAX_LOCALIZATION_TOOL_OUTPUT_BYTES = 512 * 1024;
    private static final String REASONING_TOOL = "alertify_request_reasoning_upgrade";
    private static final String LOAD_GROUP_TOOL = "alertify_load_tool_group";
    private static final String RESTORATION_HISTORY_TOOL = "alertify_restoration_history";
    private static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh");

    private final AiWorkerClient worker;
    private final AiToolCatalog catalog;
    private final AiChatPersistence persistence;
    private final AiConversationRepository conversations;
    private final AiTurnRepository turns;
    private final AiMessageRepository messages;
    private final AiToolInvocationRepository invocations;
    private final AiLocalizationSourceService localization;
    private final AdminEventPublisher events;
    private final ApplicationEventLogger eventLogger;
    private final JsonMapper json;
    private final ConcurrentMap<Long, ActiveTurn> activeTurns = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();

    public AiChatService(AiWorkerClient worker, AiToolCatalog catalog, AiChatPersistence persistence,
            AiConversationRepository conversations, AiTurnRepository turns, AiMessageRepository messages,
            AiToolInvocationRepository invocations, AiLocalizationSourceService localization, AdminEventPublisher events,
            ApplicationEventLogger eventLogger, JsonMapper json) {
        this.worker = worker;
        this.catalog = catalog;
        this.persistence = persistence;
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
        this.invocations = invocations;
        this.localization = localization;
        this.events = events;
        this.eventLogger = eventLogger;
        this.json = json;
    }

    public synchronized SubmitResponse submit(Authentication authentication, SubmitRequest request) {
        requireMessage(request.message());
        String mode = enumValue(request.mode(), Set.of("READ_ONLY", "PLAN", "DIRECT"), "READ_ONLY");
        String approval = enumValue(request.approvalPolicy(), Set.of("ASK", "AUTO"), "ASK");
        String locale = localization.resolveLocale(request.locale());
        if (request.conversationId() != null && activeTurns.values().stream()
                .anyMatch(active -> active.conversationId.equals(request.conversationId())))
            throw new ConflictException("AI_CHAT_TURN_ACTIVE", "The conversation already has an active turn", Map.of());

        var state = worker.state();
        if (!state.getEnabled() || !"ACTIVE".equals(state.getSessionStatus()))
            throw new IllegalStateException("The Codex session is not active");
        AiSettings settings = worker.settings();
        String subject = authentication.getName();
        String username = username(authentication);
        AiChatPersistence.StartedTurn started = persistence.start(subject, username, request.conversationId(),
                request.message().strip(), mode, approval, settings.getModel(), settings.getReasoningEffort());
        List<AiToolDescriptor> permitted = catalog.descriptorsFor(authentication, EnumSet.allOf(AiToolGroup.class));
        ActiveTurn active = new ActiveTurn(started.conversationId(), started.turnId(), subject, authentication, locale, mode, approval,
                settings, permitted.stream().collect(Collectors.toUnmodifiableMap(AiToolDescriptor::name, value -> value)));
        activeTurns.put(active.turnId, active);
        try {
            open(active, settings.getReasoningEffort());
        } catch (RuntimeException exception) {
            activeTurns.remove(active.turnId);
            persistence.failed(active.turnId, "AI_WORKER_FAILED", "The AI worker could not start the turn");
            throw exception;
        }
        publish(active, "RUNNING", null);
        return new SubmitResponse(active.conversationId, active.turnId);
    }

    public void approve(Authentication authentication, Long invocationId) {
        PendingApproval pending = pendingApprovals.get(invocationId);
        if (pending == null || !pending.active.subject.equals(authentication.getName())
                || !persistence.approvalIsValid(invocationId, authentication.getName()))
            throw new ResourceNotFoundException("Pending AI tool approval not found or expired");
        if (!pendingApprovals.remove(invocationId, pending))
            throw new IllegalStateException("The AI tool approval was already resolved");
        persistence.toolRunning(invocationId, "Approved by the user");
        execute(pending.active, pending.invocation, pending.descriptor, pending.callId, pending.arguments);
    }

    public void reject(Authentication authentication, Long invocationId) {
        PendingApproval pending = pendingApprovals.get(invocationId);
        if (pending == null || !pending.active.subject.equals(authentication.getName())
                || !pendingApprovals.remove(invocationId, pending))
            throw new ResourceNotFoundException("Pending AI tool approval not found");
        String message = userRejectionMessage(pending.active.locale, pending.descriptor.name());
        persistence.rejectedByUser(invocationId, message);
        finish(pending.active);
        publish(pending.active, "CANCELLED", message);
    }

    public void cancel(Authentication authentication, Long turnId) {
        ActiveTurn active = activeTurns.get(turnId);
        if (active == null || !active.subject.equals(authentication.getName()))
            throw new ResourceNotFoundException("Active AI turn not found");
        persistence.cancelled(turnId);
        finish(active);
        publish(active, "CANCELLED", null);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void expireApprovals() {
        for (Map.Entry<Long, PendingApproval> entry : pendingApprovals.entrySet()) {
            PendingApproval pending = entry.getValue();
            if (persistence.approvalIsValid(entry.getKey(), pending.active.subject)
                    || !pendingApprovals.remove(entry.getKey(), pending))
                continue;

            persistence.toolRejected(entry.getKey(), "Approval expired after 15 minutes", "APPROVAL_EXPIRED");
            persistence.cancelled(pending.active.turnId);
            finish(pending.active);
            publish(pending.active, "CANCELLED", "Approval expired");
        }
    }

    @Transactional(readOnly = true)
    public List<ConversationSummary> conversations(Authentication authentication) {
        return conversations.findAllByUserSubjectOrderByUpdatedAtDescIdDesc(authentication.getName()).stream()
                .map(value -> new ConversationSummary(value.getId(), value.getTitle(), value.getMode(), value.getApprovalPolicy(),
                        value.getStatus(), value.getCreatedAt(), value.getUpdatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetail conversation(Authentication authentication, Long id) {
        AiConversation conversation = conversations.findByIdAndUserSubject(id, authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("AI conversation not found"));
        List<MessageResponse> messageResponses = messages.findAllByConversation_IdOrderById(id).stream()
                .map(value -> new MessageResponse(value.getId(), value.getTurnId(), value.getRole(), value.getContent(), value.getCreatedAt())).toList();
        List<AiTurn> conversationTurns = turns.findAllByConversation_IdOrderById(id);
        List<TurnResponse> turnResponses = conversationTurns.stream().map(this::turn).toList();
        UsageTotalsResponse usage = new UsageTotalsResponse(conversationTurns.stream().mapToInt(AiTurn::getModelRequestCount).sum(),
                tokens(conversationTurns));
        return new ConversationDetail(conversation.getId(), conversation.getTitle(), conversation.getMode(),
                conversation.getApprovalPolicy(), conversation.getStatus(), messageResponses, turnResponses, usage);
    }

    @Transactional(readOnly = true)
    public List<ManagedConversationSummary> managedConversations() {
        return conversations.findAllByOrderByUpdatedAtDescIdDesc().stream().map(this::managedSummary).toList();
    }

    @Transactional(readOnly = true)
    public ManagedConversationDetail managedConversation(Long id) {
        AiConversation conversation = conversations.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AI conversation not found"));
        List<MessageResponse> messageResponses = messages.findAllByConversation_IdOrderById(id).stream()
                .map(value -> new MessageResponse(value.getId(), value.getTurnId(), value.getRole(), value.getContent(), value.getCreatedAt())).toList();
        List<AiTurn> conversationTurns = turns.findAllByConversation_IdOrderById(id);
        return new ManagedConversationDetail(conversation.getId(), conversation.getUserSubject(), conversation.getUsername(),
                conversation.getTitle(), conversation.getMode(), conversation.getApprovalPolicy(), conversation.getStatus(),
                conversation.getCreatedAt(), conversation.getUpdatedAt(), messageResponses,
                conversationTurns.stream().map(this::turn).toList(), usageTotals(conversationTurns));
    }

    @Transactional
    public synchronized void deleteManagedConversation(Long id) {
        AiConversation conversation = conversations.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AI conversation not found"));
        activeTurns.values().stream().filter(active -> active.conversationId.equals(id)).toList().forEach(active -> {
            persistence.cancelled(active.turnId);
            finish(active);
        });
        conversations.delete(conversation);
        eventLogger.successAfterCommit("AI_CONVERSATION_DELETED", Map.of("conversationId", id, "conversationOwner", conversation.getUsername()));
        runAfterCommit(() -> events.publishToSubject(conversation.getUserSubject(), "AI_CHAT_UPDATED", new ChatEvent(id, null, "DELETED", null)));
    }

    @Transactional
    public synchronized int deleteAllManagedConversations() {
        List<AiConversation> stored = conversations.findAllByOrderByUpdatedAtDescIdDesc();
        if (stored.isEmpty())
            return 0;

        Set<Long> ids = stored.stream().map(AiConversation::getId).collect(Collectors.toUnmodifiableSet());
        activeTurns.values().stream().filter(active -> ids.contains(active.conversationId)).toList().forEach(active -> {
            persistence.cancelled(active.turnId);
            finish(active);
        });
        conversations.deleteAllInBatch();
        eventLogger.successAfterCommit("AI_CONVERSATION_DELETED", Map.of("conversationCount", stored.size(), "all", true));
        runAfterCommit(() -> stored.forEach(conversation -> events.publishToSubject(conversation.getUserSubject(), "AI_CHAT_UPDATED",
                new ChatEvent(conversation.getId(), null, "DELETED", null))));
        return stored.size();
    }

    private void open(ActiveTurn active, String effort) {
        long generation = active.generation.incrementAndGet();
        AiWorkerClient.ChatHandle handle = worker.chat(
                message -> onMessage(active, generation, message),
                failure -> onFailure(active, generation),
                () -> onCompleted(active, generation));
        active.handle = handle;
        ChatStart.Builder start = ChatStart.newBuilder().setConversationId(active.conversationId).setTurnId(active.turnId)
                .setModel(active.settings.getModel()).setReasoningEffort(effort).setInstructions(instructions(active))
                .setMode(active.mode).setApprovalPolicy(active.approvalPolicy);
        persistence.history(active.conversationId).forEach(message -> start.addHistory(ChatHistoryMessage.newBuilder()
                .setRole(message.getRole()).setContent(message.getContent()).build()));
        start.addTools(groupLoaderTool());
        start.addTools(reasoningTool());
        start.addTools(restorationHistoryTool());
        handle.send(ChatClientMessage.newBuilder().setStart(start.build()).build());
    }

    private void onMessage(ActiveTurn active, long generation, ChatWorkerMessage message) {
        if (!current(active, generation)) {
            if (activeTurns.get(active.turnId) == active && message.getPayloadCase() == ChatWorkerMessage.PayloadCase.TOOL_CALL)
                auditStaleToolCall(active, generation, message.getToolCall().getCallId(), message.getToolCall().getName(), message.getToolCall().getArguments());

            return;
        }
        switch (message.getPayloadCase()) {
            case OUTPUT_TEXT -> {
                String text = message.getOutputText().strip();
                if (!text.isBlank())
                    persistence.assistant(active.conversationId, active.turnId, text);
                publish(active, "MESSAGE", text);
            }
            case TOOL_CALL -> toolCall(active, generation, message.getToolCall().getCallId(), message.getToolCall().getName(), message.getToolCall().getArguments());
            case USAGE -> {
                persistence.usage(active.turnId, message.getUsage());
                publish(active, "USAGE", null);
            }
            case COMPLETED -> {
                persistence.completed(active.turnId);
                finish(active);
                publish(active, "COMPLETED", null);
            }
            case FAILURE -> {
                persistence.failed(active.turnId, message.getFailure().getCode(), message.getFailure().getMessage());
                finish(active);
                publish(active, "FAILED", message.getFailure().getMessage());
            }
            case PAYLOAD_NOT_SET -> fail(active, "AI_PROTOCOL_INVALID", "The AI worker returned an empty frame");
        }
    }

    private void toolCall(ActiveTurn active, long generation, String callId, String name, String arguments) {
        ToolAudit audit = toolAudit(active, name);
        boolean approval = audit.descriptor() != null && audit.allowed() && !audit.readOnly() && "ASK".equals(active.approvalPolicy);
        AiToolInvocation invocation = persistence.tool(active.turnId, generation, callId, name,
                bounded(arguments, MAX_TOOL_ARGUMENT_BYTES), audit.kind(), audit.declared(), audit.allowed(), audit.readOnly(),
                audit.destructiveHint(), audit.idempotentHint(), audit.openWorldHint(), audit.refreshTargets(), approval);
        if (!audit.allowed()) {
            String code = audit.declared() ? "TOOL_NOT_ALLOWED" : "TOOL_UNKNOWN";
            String output = audit.declared() ? "Failed: this tool is not allowed in the current mode." : "Failed: unknown tool.";
            persistence.toolDenied(invocation.getId(), output, code);
            sendResult(active, callId, output, true, code);
            publish(active, "TOOL_RESULT", invocation.getId());
            return;
        }
        if (REASONING_TOOL.equals(name)) {
            upgrade(active, invocation, callId);
            return;
        }
        if (LOAD_GROUP_TOOL.equals(name)) {
            loadGroup(active, invocation, callId, arguments);
            return;
        }
        if (RESTORATION_HISTORY_TOOL.equals(name)) {
            restorationHistory(active, invocation, callId, arguments);
            return;
        }
        AiToolDescriptor descriptor = audit.descriptor();
        if (approval) {
            PendingApproval pending = new PendingApproval(active, invocation, descriptor, callId, arguments);
            pendingApprovals.put(invocation.getId(), pending);
            publish(active, "APPROVAL_REQUIRED", invocation.getId());
            return;
        }
        if (!descriptor.readOnly() && "AUTO".equals(active.approvalPolicy)) {
            var request = ReviewToolCallRequest.newBuilder()
                    .setModel(reviewerModel(active.settings)).setReasoningEffort(reviewerEffort(active.settings))
                    .setConversationId(active.conversationId).setTurnId(active.turnId)
                    .setUserRequest(latestUserMessage(active.conversationId)).setToolName(descriptor.name())
                    .setToolDescription(descriptor.description()).setArguments(arguments)
                    .setDestructiveHint(descriptor.destructiveHint()).setIdempotentHint(descriptor.idempotentHint())
                    .setOpenWorldHint(descriptor.openWorldHint()).build();
            app.alertify.worker.ai.grpc.ReviewToolCallResponse review;
            try {
                review = worker.review(request);
            } catch (RuntimeException exception) {
                String output = "Failed: the automatic reviewer could not evaluate this action.";
                persistence.toolFailed(invocation.getId(), output, "REVIEW_FAILED");
                sendResult(active, callId, output, true, "REVIEW_FAILED");
                publish(active, "TOOL_RESULT", invocation.getId());
                return;
            }
            if (review.hasUsage()) {
                persistence.usage(active.turnId, review.getUsage());
                publish(active, "USAGE", null);
            }
            if (!review.getAllowed()) {
                persistence.toolRejected(invocation.getId(), review.getReason(), "REVIEW_REJECTED");
                if (++active.autoRejections >= 3) {
                    fail(active, "AI_REVIEW_LIMIT", "The automatic reviewer rejected three proposed actions");
                    return;
                }
                sendResult(active, callId, "Failed: the automatic reviewer rejected this action. Choose a safer alternative. Reason: " + review.getReason(), true, "REVIEW_REJECTED");
                return;
            }
            persistence.toolRunning(invocation.getId(), review.getReason());
        }
        execute(active, invocation, descriptor, callId, arguments);
    }

    private void execute(ActiveTurn active, AiToolInvocation invocation, AiToolDescriptor descriptor, String callId, String arguments) {
        String output;
        boolean failed = false;
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(active.authentication);
        SecurityContextHolder.setContext(context);
        try {
            output = AiConversationContext.callWith(active.conversationId, active.locale, () -> descriptor.callback().call(arguments));
            int maxOutputBytes = LocalizationAiTools.TOOL_NAME.equals(descriptor.name())
                    ? MAX_LOCALIZATION_TOOL_OUTPUT_BYTES : MAX_TOOL_OUTPUT_BYTES;
            output = bounded(output == null ? "null" : output, maxOutputBytes);
            persistence.toolSucceeded(invocation.getId(), output);
            if (!descriptor.readOnly()) {
                active.successfulWrite = true;
                events.publish("AI_RESOURCES_INVALIDATED", Map.of("targets", descriptor.refreshTargets().stream().map(Enum::name).toList()));
            }
        } catch (RuntimeException exception) {
            failed = true;
            output = "Failed: " + safeFailure(exception);
            persistence.toolFailed(invocation.getId(), output, "TOOL_EXECUTION_FAILED");
        } finally {
            SecurityContextHolder.setContext(previous);
        }
        sendResult(active, callId, output, failed, failed ? "TOOL_EXECUTION_FAILED" : "");
        publish(active, "TOOL_RESULT", invocation.getId());
    }

    private void upgrade(ActiveTurn active, AiToolInvocation invocation, String callId) {
        int index = EFFORTS.indexOf(active.effort);
        if (active.upgraded || active.successfulWrite || index < 0 || index == EFFORTS.size() - 1) {
            internalResult(active, invocation, callId, "Failed: reasoning cannot be upgraded for this turn.", true, "REASONING_UPGRADE_UNAVAILABLE");
            return;
        }
        active.upgraded = true;
        active.effort = EFFORTS.get(index + 1);
        active.loadedGroups.clear();
        persistence.upgraded(active.turnId, active.effort);
        active.generation.incrementAndGet();
        AiWorkerClient.ChatHandle previous = active.handle;
        if (previous != null)
            previous.close();
        try {
            open(active, active.effort);
            persistence.toolSucceeded(invocation.getId(), "Reasoning upgraded to " + active.effort + ".");
            publish(active, "TOOL_RESULT", invocation.getId());
            publish(active, "REASONING_UPGRADED", active.effort);
        } catch (RuntimeException exception) {
            persistence.toolFailed(invocation.getId(), "Failed: the upgraded model request could not be started.", "REASONING_UPGRADE_FAILED");
            fail(active, "AI_WORKER_FAILED", "The AI worker could not restart the turn with upgraded reasoning");
        }
    }

    private void loadGroup(ActiveTurn active, AiToolInvocation invocation, String callId, String arguments) {
        try {
            String value = json.readTree(arguments).path("group").asText("").toUpperCase(Locale.ROOT);
            AiToolGroup group = AiToolGroup.valueOf(value);
            if (!active.loadedGroups.add(group)) {
                internalResult(active, invocation, callId, "Tool group " + group + " was already loaded.", false, "");
                return;
            }
            List<ChatToolDefinition> tools = catalog.descriptorsFor(active.authentication, Set.of(group)).stream()
                    .filter(descriptor -> "DIRECT".equals(active.mode) || descriptor.readOnly())
                    .map(AiChatService::tool).toList();
            internalResult(active, invocation, callId, "Loaded " + tools.size() + " tools from group " + group + ".", false, "", tools);
        } catch (RuntimeException exception) {
            internalResult(active, invocation, callId, "Failed: unknown or invalid tool group.", true, "UNKNOWN_TOOL_GROUP");
        }
    }

    private void restorationHistory(ActiveTurn active, AiToolInvocation invocation, String callId, String arguments) {
        try {
            var input = json.readTree(arguments);
            var queryNode = input.path("query");
            var limitNode = input.path("limit");
            String query = queryNode.isTextual() ? queryNode.textValue().strip() : "";
            int limit = limitNode.isIntegralNumber() ? limitNode.intValue() : 10;
            if (limit < 1 || limit > 20) {
                internalResult(active, invocation, callId, "Failed: limit must be between 1 and 20.", true, "INVALID_RESTORATION_HISTORY_LIMIT");
                return;
            }

            List<RestorationHistoryEntry> entries = persistence.restorationHistory(active.conversationId, active.turnId, query, limit)
                    .stream().map(entry -> new RestorationHistoryEntry(entry.getId(), entry.getTurn().getId(),
                            entry.getToolName(), entry.getArguments(), entry.getResult(), entry.getFinishedAt()))
                    .toList();
            internalResult(active, invocation, callId, bounded(json.writeValueAsString(entries), MAX_TOOL_OUTPUT_BYTES), false, "");
        } catch (RuntimeException exception) {
            internalResult(active, invocation, callId, "Failed: restoration history could not be read.", true, "RESTORATION_HISTORY_FAILED");
        }
    }

    private void internalResult(ActiveTurn active, AiToolInvocation invocation, String callId, String output, boolean failed, String diagnosticCode) {
        internalResult(active, invocation, callId, output, failed, diagnosticCode, List.of());
    }

    private void internalResult(ActiveTurn active, AiToolInvocation invocation, String callId, String output, boolean failed, String diagnosticCode, List<ChatToolDefinition> additionalTools) {
        if (failed)
            persistence.toolFailed(invocation.getId(), output, diagnosticCode);
        else
            persistence.toolSucceeded(invocation.getId(), output);

        sendResult(active, callId, output, failed, diagnosticCode, additionalTools);
        publish(active, "TOOL_RESULT", invocation.getId());
    }

    private void auditStaleToolCall(ActiveTurn active, long generation, String callId, String name, String arguments) {
        ToolAudit audit = toolAudit(active, name);
        AiToolInvocation invocation = persistence.tool(active.turnId, generation, callId, name,
                bounded(arguments, MAX_TOOL_ARGUMENT_BYTES), audit.kind(), audit.declared(), false, audit.readOnly(),
                audit.destructiveHint(), audit.idempotentHint(), audit.openWorldHint(), audit.refreshTargets(), false);
        persistence.toolDenied(invocation.getId(), "Ignored: tool call belongs to an inactive model stream.", "STALE_STREAM_GENERATION");
        publish(active, "TOOL_RESULT", invocation.getId());
    }

    private static ToolAudit toolAudit(ActiveTurn active, String name) {
        if (REASONING_TOOL.equals(name))
            return new ToolAudit(null, "INTERNAL", true, true, true, false, false, false, "");
        if (LOAD_GROUP_TOOL.equals(name) || RESTORATION_HISTORY_TOOL.equals(name))
            return new ToolAudit(null, "INTERNAL", true, true, true, false, true, false, "");

        AiToolDescriptor descriptor = active.descriptors.get(name);
        if (descriptor == null)
            return new ToolAudit(null, "UNKNOWN", false, false, false, false, false, false, "");

        boolean allowed = "DIRECT".equals(active.mode) || descriptor.readOnly();
        String refreshTargets = descriptor.refreshTargets().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return new ToolAudit(descriptor, "DOMAIN", true, allowed, descriptor.readOnly(), descriptor.destructiveHint(),
                descriptor.idempotentHint(), descriptor.openWorldHint(), refreshTargets);
    }

    private void sendResult(ActiveTurn active, String callId, String output, boolean failed) {
        sendResult(active, callId, output, failed, "", List.of());
    }

    private void sendResult(ActiveTurn active, String callId, String output, boolean failed, List<ChatToolDefinition> additionalTools) {
        sendResult(active, callId, output, failed, "", additionalTools);
    }

    private void sendResult(ActiveTurn active, String callId, String output, boolean failed, String diagnosticCode) {
        sendResult(active, callId, output, failed, diagnosticCode, List.of());
    }

    private void sendResult(ActiveTurn active, String callId, String output, boolean failed, String diagnosticCode, List<ChatToolDefinition> additionalTools) {
        AiWorkerClient.ChatHandle handle = active.handle;
        if (handle != null)
            handle.send(ChatClientMessage.newBuilder().setToolResult(ChatToolResult.newBuilder()
                    .setCallId(callId).setOutput(output).setFailed(failed).setDiagnosticCode(diagnosticCode)
                    .addAllAdditionalTools(additionalTools).build()).build());
    }

    private void fail(ActiveTurn active, String code, String message) {
        persistence.failed(active.turnId, code, message);
        finish(active);
        publish(active, "FAILED", message);
    }

    private void finish(ActiveTurn active) {
        activeTurns.remove(active.turnId, active);
        pendingApprovals.entrySet().removeIf(entry -> entry.getValue().active == active);
        AiWorkerClient.ChatHandle handle = active.handle;
        active.handle = null;
        if (handle != null)
            handle.close();
    }

    private void onFailure(ActiveTurn active, long generation) {
        if (current(active, generation))
            fail(active, "AI_WORKER_STREAM_FAILED", "The AI worker stream failed");
    }

    private void onCompleted(ActiveTurn active, long generation) {
        if (current(active, generation) && activeTurns.containsKey(active.turnId))
            fail(active, "AI_WORKER_STREAM_CLOSED", "The AI worker stream closed before completing the turn");
    }

    private boolean current(ActiveTurn active, long generation) {
        return activeTurns.get(active.turnId) == active && active.generation.get() == generation;
    }

    private void publish(ActiveTurn active, String status, Object detail) {
        events.publishToSubject(active.subject, "AI_CHAT_UPDATED", new ChatEvent(active.conversationId, active.turnId, status, detail));
    }

    private String instructions(ActiveTurn active) {
        StringBuilder value = new StringBuilder("You are Alertify's administrative assistant. Use tools only when needed. ")
                .append("Never invent IDs, versions, configurations, secrets, or tool results. ")
                .append("The current mode is ").append(active.mode).append(". ")
                .append("Load a tool group before using its domain tools with ").append(LOAD_GROUP_TOOL)
                .append(". Groups: ALERTS definitions and executions; PROCEDURES procedures and runs; PIPES pipelines; ")
                .append("HOOKS inbound hooks; CONFIGURATION configurations, secret references, and system configuration; ")
                .append("DASHBOARD current dashboard state; LOCALIZATION exact frontend translations for the selected language; ")
                .append("SYSTEM operational state. The user's selected frontend locale is ").append(active.locale).append(". ")
                .append("The current server time is ").append(Instant.now()).append(". Use it to calculate relative times and explicit execution-history ranges. ")
                .append(schedulingTimeZoneInstruction(ZoneId.systemDefault(), Instant.now()))
                .append("The write approval policy is ").append(active.approvalPolicy).append(". ")
                .append("When an answer depends on an Alertify UI label, template name, or template description, load LOCALIZATION ")
                .append("and use its exact selected-language sources. ")
                .append("Before answering about an alert or procedure template, identify its type and exact technical templateKey from the matching catalog. ")
                .append("For claims about executable behavior or implementation, inspect the source for that same templateKey with alertify_alert_template_source or alertify_procedure_template_source, as appropriate. ")
                .append("Do not substitute a similarly named template from another catalog. Localization sources establish interface labels and descriptions only; never present them as verification of executable behavior. ")
                .append("Before any destructive action, including deleting a resource or overwriting existing data, first inspect its complete current state with read-only tools. ")
                .append("In the same model response and before the destructive tool call, write a clearly labeled restoration snapshot to the chat with every non-secret field and relationship available through the tools that is needed to recreate the deleted resource or undo the edit. ")
                .append("Never expose secret values, credentials, tokens, or other protected data; explicitly identify protected fields that cannot be recovered. ")
                .append("When the user asks to restore, undo, or recreate a prior state, always call ").append(RESTORATION_HISTORY_TOOL)
                .append(" using the resource name or type as the query before claiming that data is unavailable. Use its audited arguments and results to reconstruct the last applicable non-secret state. ");
        if ("ASK".equals(active.approvalPolicy))
            value.append("When a useful restoration snapshot cannot be produced, clearly explain what cannot be restored and still submit the destructive tool call for the user's explicit approval. ");
        else
            value.append("Do not perform the destructive action when a useful restoration snapshot cannot be produced. ");

        if ("READ_ONLY".equals(active.mode))
            value.append("You can inspect state but cannot mutate it. Explain that limitation when the requested result requires a write. ");
        if ("PLAN".equals(active.mode)) {
            value.append("Inspect with read-only tools and return a plan. Do not claim that changes were applied. Available write operations include: ");
            active.descriptors.values().stream().filter(descriptor -> !descriptor.readOnly())
                    .forEach(descriptor -> value.append(descriptor.name()).append("; "));
        }
        value.append("You may call ").append(REASONING_TOOL)
                .append(", before any successful write, when the request truly needs a higher reasoning effort. ");
        if (active.settings.getAgentInstructions() != null && !active.settings.getAgentInstructions().isBlank())
            value.append("\n\nAlertify agent instructions:\n").append(active.settings.getAgentInstructions());
        return value.toString();
    }

    static String schedulingTimeZoneInstruction(ZoneId zoneId, Instant now) {
        return "Alert and procedure cron expressions are evaluated in the Alertify backend time zone " + zoneId.getId()
                + " (current UTC offset " + zoneId.getRules().getOffset(now) + "). ";
    }

    private static ChatToolDefinition tool(AiToolDescriptor descriptor) {
        return ChatToolDefinition.newBuilder().setName(descriptor.name()).setDescription(descriptor.description())
                .setInputSchema(descriptor.inputSchema()).setReadOnly(descriptor.readOnly())
                .setDestructiveHint(descriptor.destructiveHint()).setIdempotentHint(descriptor.idempotentHint())
                .setOpenWorldHint(descriptor.openWorldHint())
                .addAllRefreshTargets(descriptor.refreshTargets().stream().map(Enum::name).toList()).build();
    }

    private static ChatToolDefinition reasoningTool() {
        return ChatToolDefinition.newBuilder().setName(REASONING_TOOL)
                .setDescription("Restart this turn once with the next higher reasoning effort before any write succeeds")
                .setInputSchema("{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\"}},\"required\":[\"reason\"],\"additionalProperties\":false}")
                .setReadOnly(true).setIdempotentHint(false).build();
    }

    private static ChatToolDefinition groupLoaderTool() {
        return ChatToolDefinition.newBuilder().setName(LOAD_GROUP_TOOL)
                .setDescription("Load one bounded group of Alertify tools before calling a domain tool")
                .setInputSchema("{\"type\":\"object\",\"properties\":{\"group\":{\"type\":\"string\",\"enum\":[\"ALERTS\",\"PROCEDURES\",\"PIPES\",\"HOOKS\",\"CONFIGURATION\",\"DASHBOARD\",\"LOCALIZATION\",\"SYSTEM\"]}},\"required\":[\"group\"],\"additionalProperties\":false}")
                .setReadOnly(true).setIdempotentHint(true).build();
    }

    private static ChatToolDefinition restorationHistoryTool() {
        return ChatToolDefinition.newBuilder().setName(RESTORATION_HISTORY_TOOL)
                .setDescription("Read recent successful tool arguments and results from this authenticated conversation to restore or undo a prior resource state. Use a resource name or type as query; use an empty query only when the resource is unknown.")
                .setInputSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"required\":[\"query\",\"limit\"],\"additionalProperties\":false}")
                .setReadOnly(true).setIdempotentHint(true).build();
    }

    private String latestUserMessage(Long conversationId) {
        List<AiMessage> history = persistence.history(conversationId);
        for (int index = history.size() - 1; index >= 0; index--)
            if ("USER".equals(history.get(index).getRole()))
                return history.get(index).getContent();
        return "";
    }

    private ToolInvocationResponse invocation(AiToolInvocation value) {
        return new ToolInvocationResponse(value.getId(), value.getCallId(), value.getGeneration(), value.getToolName(), value.getKind(),
                value.isDeclared(), value.isAllowed(), value.getArguments(), value.getResult(), value.getStatus(), value.isReadOnly(),
                value.isDestructiveHint(), value.isIdempotentHint(), value.isOpenWorldHint(),
                value.getRefreshTargets().isBlank() ? List.of() : Arrays.asList(value.getRefreshTargets().split(",")),
                value.getReviewReason(), value.getDiagnosticCode(), value.getCreatedAt(), value.getFinishedAt());
    }

    private TurnResponse turn(AiTurn value) {
        return new TurnResponse(value.getId(), value.getStatus(), value.getModel(), value.getReasoningEffort(),
                value.isReasoningUpgraded(), value.getRejectionCount(), value.getErrorCode(), value.getErrorMessage(), value.getCreatedAt(),
                value.getFinishedAt(), usage(value),
                invocations.findAllByTurn_IdOrderById(value.getId()).stream().map(this::invocation).toList());
    }

    private ManagedConversationSummary managedSummary(AiConversation value) {
        return new ManagedConversationSummary(value.getId(), value.getUserSubject(), value.getUsername(), value.getTitle(),
                value.getMode(), value.getApprovalPolicy(), value.getStatus(), value.getCreatedAt(), value.getUpdatedAt());
    }

    private static UsageTotalsResponse usageTotals(List<AiTurn> values) {
        return new UsageTotalsResponse(values.stream().mapToInt(AiTurn::getModelRequestCount).sum(), tokens(values));
    }

    private static TurnUsageResponse usage(AiTurn value) {
        AiQuotaSnapshotValue snapshot = value.getQuotaSnapshot();
        QuotaSnapshotResponse quota = snapshot == null ? null : new QuotaSnapshotResponse(snapshot.planType(),
                snapshot.windows().stream().map(window -> new RateLimitWindowResponse(window.key(), window.label(),
                        window.usedPercent(), window.remainingPercent(), window.limit(), window.remaining(),
                        window.windowMinutes(), epoch(window.resetsAtEpochSeconds()))).toList(),
                epoch(snapshot.observedAtEpochSeconds()));
        return new TurnUsageResponse(value.getModelRequestCount(), tokens(value), value.getLastRequestTotalTokens(),
                value.getModelContextWindow(), quota, value.getUsageObservedAt());
    }

    private static TokenUsageResponse tokens(AiTurn value) {
        return new TokenUsageResponse(value.getInputTokens(), value.getCachedInputTokens(), value.getCacheWriteInputTokens(),
                value.getOutputTokens(), value.getReasoningOutputTokens(), value.getTotalTokens());
    }

    private static TokenUsageResponse tokens(List<AiTurn> values) {
        long input = 0;
        long cached = 0;
        long cacheWrite = 0;
        long output = 0;
        long reasoning = 0;
        long total = 0;
        for (AiTurn value : values) {
            input = Math.addExact(input, value.getInputTokens());
            cached = Math.addExact(cached, value.getCachedInputTokens());
            cacheWrite = Math.addExact(cacheWrite, value.getCacheWriteInputTokens());
            output = Math.addExact(output, value.getOutputTokens());
            reasoning = Math.addExact(reasoning, value.getReasoningOutputTokens());
            total = Math.addExact(total, value.getTotalTokens());
        }
        return new TokenUsageResponse(input, cached, cacheWrite, output, reasoning, total);
    }

    private static Instant epoch(Long value) {
        return value == null || value <= 0 ? null : Instant.ofEpochSecond(value);
    }

    private static String reviewerModel(AiSettings settings) {
        return settings.getReviewerModel().isBlank() ? settings.getModel() : settings.getReviewerModel();
    }

    private static String reviewerEffort(AiSettings settings) {
        return settings.getReviewerReasoningEffort().isBlank() ? "low" : settings.getReviewerReasoningEffort();
    }

    private static String username(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String username = jwt.getToken().getClaimAsString("preferred_username");
            if (username != null && !username.isBlank())
                return username;
        }
        return authentication.getName();
    }

    static String userRejectionMessage(String locale, String toolName) {
        String emphasizedToolName = "**" + toolName + "**";
        if (locale != null && locale.toLowerCase(Locale.ROOT).startsWith("es"))
            return "El usuario rechazó la acción solicitada " + emphasizedToolName + ".";

        return "The user rejected the requested action " + emphasizedToolName + ".";
    }

    private static void requireMessage(String message) {
        if (message == null || message.isBlank() || message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES)
            throw new IllegalArgumentException("The chat message must contain between 1 byte and 32 KiB");
    }

    private static String enumValue(String value, Set<String> allowed, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized))
            throw new IllegalArgumentException("Unsupported AI chat option: " + normalized);
        return normalized;
    }

    private static String bounded(String value, int maxBytes) {
        if (value == null)
            return "";

        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes)
            return value;
        return value.substring(0, Math.min(value.length(), maxBytes / 2)) + "\n[Result truncated by Alertify]";
    }

    private static String safeFailure(RuntimeException exception) {
        return "Tool execution failed (" + exception.getClass().getSimpleName() + ")";
    }

    private static void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static final class ActiveTurn {
        private final Long conversationId;
        private final Long turnId;
        private final String subject;
        private final Authentication authentication;
        private final String locale;
        private final String mode;
        private final String approvalPolicy;
        private final AiSettings settings;
        private final Map<String, AiToolDescriptor> descriptors;
        private final AtomicLong generation = new AtomicLong();
        private final Set<AiToolGroup> loadedGroups = ConcurrentHashMap.newKeySet();
        private volatile AiWorkerClient.ChatHandle handle;
        private volatile String effort;
        private volatile boolean upgraded;
        private volatile boolean successfulWrite;
        private volatile int autoRejections;

        private ActiveTurn(Long conversationId, Long turnId, String subject, Authentication authentication, String locale, String mode,
                String approvalPolicy, AiSettings settings, Map<String, AiToolDescriptor> descriptors) {
            this.conversationId = conversationId;
            this.turnId = turnId;
            this.subject = subject;
            this.authentication = authentication;
            this.locale = locale;
            this.mode = mode;
            this.approvalPolicy = approvalPolicy;
            this.settings = settings;
            this.descriptors = descriptors;
            effort = settings.getReasoningEffort();
        }
    }

    private record PendingApproval(ActiveTurn active, AiToolInvocation invocation, AiToolDescriptor descriptor,
            String callId, String arguments) {
    }

    private record ToolAudit(AiToolDescriptor descriptor, String kind, boolean declared, boolean allowed,
            boolean readOnly, boolean destructiveHint, boolean idempotentHint, boolean openWorldHint, String refreshTargets) {
    }

    public record SubmitRequest(Long conversationId, String message, String mode, String approvalPolicy, String locale) {
    }

    public record SubmitResponse(Long conversationId, Long turnId) {
    }

    public record ConversationSummary(Long id, String title, String mode, String approvalPolicy, String status, Instant createdAt, Instant updatedAt) {
    }

    public record ConversationDetail(Long id, String title, String mode, String approvalPolicy, String status,
            List<MessageResponse> messages, List<TurnResponse> turns, UsageTotalsResponse usage) {
    }

    public record MessageResponse(Long id, Long turnId, String role, String content, Instant createdAt) {
    }

    public record TurnResponse(Long id, String status, String model, String reasoningEffort, boolean reasoningUpgraded,
            int rejectionCount, String errorCode, String errorMessage, Instant createdAt, Instant finishedAt, TurnUsageResponse usage,
            List<ToolInvocationResponse> tools) {
    }

    public record ManagedConversationSummary(Long id, String userSubject, String username, String title, String mode,
            String approvalPolicy, String status, Instant createdAt, Instant updatedAt) {
    }

    public record ManagedConversationDetail(Long id, String userSubject, String username, String title, String mode,
            String approvalPolicy, String status, Instant createdAt, Instant updatedAt, List<MessageResponse> messages,
            List<TurnResponse> turns, UsageTotalsResponse usage) {
    }

    public record TokenUsageResponse(long inputTokens, long cachedInputTokens, long cacheWriteInputTokens,
            long outputTokens, long reasoningOutputTokens, long totalTokens) {
    }

    public record UsageTotalsResponse(int requestCount, TokenUsageResponse tokens) {
    }

    public record TurnUsageResponse(int requestCount, TokenUsageResponse tokens, long lastRequestTotalTokens,
            Long modelContextWindow, QuotaSnapshotResponse quota, Instant observedAt) {
    }

    public record QuotaSnapshotResponse(String planType, List<RateLimitWindowResponse> windows, Instant observedAt) {
    }

    public record RateLimitWindowResponse(String key, String label, Double usedPercent, Double remainingPercent,
            Long limit, Long remaining, Long windowMinutes, Instant resetsAt) {
    }

    public record ToolInvocationResponse(Long id, String callId, long generation, String toolName, String kind,
            boolean declared, boolean allowed, String arguments, String result, String status, boolean readOnly,
            boolean destructiveHint, boolean idempotentHint, boolean openWorldHint, List<String> refreshTargets,
            String reviewReason, String diagnosticCode, Instant createdAt, Instant finishedAt) {
    }

    private record RestorationHistoryEntry(Long invocationId, Long turnId, String toolName, String arguments,
            String result, Instant finishedAt) {
    }

    private record ChatEvent(Long conversationId, Long turnId, String status, Object detail) {
    }
}
