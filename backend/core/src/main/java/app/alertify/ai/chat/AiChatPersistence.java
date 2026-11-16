package app.alertify.ai.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.worker.ai.grpc.ChatUsageUpdate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class AiChatPersistence {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final List<String> ACTIVE_TOOL_STATUSES = List.of("PENDING_APPROVAL", "RUNNING");

    private final AiConversationRepository conversations;
    private final AiTurnRepository turns;
    private final AiMessageRepository messages;
    private final AiToolInvocationRepository invocations;

    AiChatPersistence(AiConversationRepository conversations, AiTurnRepository turns, AiMessageRepository messages, AiToolInvocationRepository invocations) {
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
        this.invocations = invocations;
    }

    @Transactional
    StartedTurn start(String subject, String username, Long conversationId, String content, String mode, String approvalPolicy, String model, String effort) {
        AiConversation conversation;
        if (conversationId == null) {
            conversation = conversations.save(new AiConversation(subject, username, title(content), mode, approvalPolicy));
        } else {
            conversation = conversations.findByIdAndUserSubject(conversationId, subject)
                    .orElseThrow(() -> new ResourceNotFoundException("AI conversation not found"));
            conversation.configure(mode, approvalPolicy);
        }
        AiTurn turn = turns.save(new AiTurn(conversation, model, effort));
        messages.save(new AiMessage(conversation, turn, "USER", content));
        conversation.touch();
        return new StartedTurn(conversation.getId(), turn.getId());
    }

    @Transactional(readOnly = true)
    List<AiMessage> history(Long conversationId) {
        return messages.findAllByConversation_IdOrderById(conversationId);
    }

    @Transactional(readOnly = true)
    JsonNode objective(String subject, Long conversationId) {
        return AiChatObjectiveState.read(conversations.findByIdAndUserSubject(conversationId, subject)
                .orElseThrow(() -> new ResourceNotFoundException("AI conversation not found")).getObjectiveDocument());
    }

    @Transactional
    AiChatObjectiveState.Change updateObjective(String subject, Long conversationId, Long turnId, JsonNode input) {
        AiConversation conversation = conversations.findLocked(conversationId, subject)
                .orElseThrow(() -> new ResourceNotFoundException("AI conversation not found"));
        AiTurn turn = turns.findById(turnId).orElseThrow();
        if (!turn.getConversation().getId().equals(conversationId) || !List.of("RUNNING", "WAITING_APPROVAL").contains(turn.getStatus()))
            throw new IllegalArgumentException("The objective turn is no longer active");

        List<AiMessage> sources = messages.findAllByConversation_IdOrderById(conversationId);
        long source = sources.stream().filter(message -> "USER".equals(message.getRole()) && turnId.equals(message.getTurnId()))
                .map(AiMessage::getId).mapToLong(Long::longValue).max().orElse(0);
        List<AiToolInvocation> evidence = new ArrayList<>();
        List<String> sensitive = new ArrayList<>();
        List<JsonNode> stepSources = new ArrayList<>(input.path("steps").valueStream().toList());
        JsonNode currentObjective = AiChatObjectiveState.read(conversation.getObjectiveDocument());
        if (currentObjective != null) stepSources.addAll(currentObjective.path("steps").valueStream().toList());
        for (JsonNode step : stepSources) {
            for (JsonNode id : step.path("evidenceInvocationIds")) {
                if (!id.isIntegralNumber() || !id.canConvertToLong()) continue;

                AiToolInvocation invocation = invocations.findByIdAndTurn_Conversation_UserSubject(id.asLong(), subject)
                        .filter(value -> value.getTurn().getConversation().getId().equals(conversationId) && value.getTurn().getId() <= turnId)
                        .orElseThrow(() -> new IllegalArgumentException("Objective evidence is outside this conversation"));
                evidence.add(invocation);
                collectSensitive(invocation.getArguments(), sensitive);
                collectSensitive(invocation.getResult(), sensitive);
            }
        }
        AiChatObjectiveState.Change change = AiChatObjectiveState.change(currentObjective, input, source, turnId, evidence, sensitive);
        if (change.changed()) conversation.objectiveDocument(JSON.writeValueAsString(change.document()));

        return change;
    }

    private static void collectSensitive(String value, List<String> sensitive) {
        if (value == null) return;

        try { collectSensitive(JSON.readTree(value), sensitive); }
        catch (RuntimeException ignored) { /* Plain tool text is never copied into objective metadata. */ }
    }

    private static void collectSensitive(JsonNode value, List<String> sensitive) {
        if (value == null) return;

        if (value.isObject()) value.properties().forEach(entry -> {
            if (entry.getKey().matches("(?i).*(password|token|secret|authorization|credential).*|(?i)value") && entry.getValue().isString()) sensitive.add(entry.getValue().asString());
            else collectSensitive(entry.getValue(), sensitive);
        });
        else if (value.isArray()) value.forEach(child -> collectSensitive(child, sensitive));
    }

    private void interruptObjective(AiConversation conversation, Long turnId, String reason) {
        JsonNode previous = AiChatObjectiveState.read(conversation.getObjectiveDocument());
        if (previous == null) return;

        boolean participating = messages.findAllByConversation_IdOrderById(conversation.getId()).stream()
                .anyMatch(message -> "USER".equals(message.getRole()) && turnId.equals(message.getTurnId())
                        && previous.path("sourceMessageIds").valueStream().anyMatch(id -> id.asLong() == message.getId()));
        if (!participating) return;

        JsonNode next = AiChatObjectiveState.interrupted(previous, reason);
        if (next != previous) conversation.objectiveDocument(JSON.writeValueAsString(next));
    }

    @Transactional(readOnly = true)
    List<AiMessage> reviewContext(String subject, Long conversationId, Long throughTurnId, Instant throughCreatedAt) {
        return messages.findReviewContext(subject, conversationId, throughTurnId, throughCreatedAt, PageRequest.of(0, AiChatReviewConversation.MAX_MESSAGES + 1));
    }

    @Transactional(readOnly = true)
    List<AiToolInvocation> operationHistory(Long conversationId, Long throughTurnId, long beforeId, int limit) {
        return invocations.findOperationHistory(conversationId, throughTurnId, beforeId, PageRequest.of(0, limit + 1));
    }

    @Transactional(readOnly = true)
    List<AiToolInvocation> reviewHistory(Long conversationId, Long throughTurnId, Long beforeInvocationId) {
        return invocations.findReviewHistory(conversationId, throughTurnId, beforeInvocationId, PageRequest.of(0, AiChatReviewHistory.MAX_ENTRIES + 1));
    }

    @Transactional(readOnly = true)
    List<AiToolInvocation> restorationHistory(Long conversationId, Long throughTurnId, String query, int limit) {
        return invocations.findRestorationHistory(conversationId, throughTurnId, query.toLowerCase(Locale.ROOT), PageRequest.of(0, limit));
    }

    @Transactional
    void assistant(Long conversationId, Long turnId, String content) {
        AiConversation conversation = conversations.getReferenceById(conversationId);
        AiTurn turn = turns.getReferenceById(turnId);
        messages.save(new AiMessage(conversation, turn, "ASSISTANT", content));
        conversation.touch();
    }

    @Transactional
    AiToolInvocation tool(Long turnId, long generation, String callId, String toolName, String arguments, String kind, boolean declared, boolean allowed, boolean readOnly, boolean destructiveHint, boolean idempotentHint, boolean openWorldHint, String refreshTargets, boolean waitingApproval) {
        AiTurn turn = turns.findById(turnId).orElseThrow();
        if (waitingApproval)
            turn.waitingApproval();

        return invocations.save(new AiToolInvocation(turn, generation, callId, toolName, arguments, kind, declared, allowed,
                readOnly, destructiveHint, idempotentHint, openWorldHint, refreshTargets, waitingApproval));
    }

    @Transactional
    void toolApproved(Long invocationId, String reason, String actorType, String actor, String subject) {
        AiToolInvocation invocation = invocations.findById(invocationId).orElseThrow();
        invocation.approvalDecision("APPROVED", actorType, actor, subject);
        invocation.running(reason);
        invocation.getTurn().running();
    }

    @Transactional
    void toolSucceeded(Long invocationId, String output) {
        invocations.findById(invocationId).orElseThrow().succeed(output);
    }

    @Transactional
    void toolFailed(Long invocationId, String output, String code) {
        invocations.findById(invocationId).orElseThrow().fail(output, code);
    }

    @Transactional
    void toolReviewRejected(Long invocationId, String reason, String model) {
        invocations.findById(invocationId).orElseThrow().approvalDecision("REJECTED", "AUTOMATIC_REVIEWER", model, null);
        toolRejected(invocationId, reason, "REVIEW_REJECTED");
    }

    @Transactional
    void toolReviewFailed(Long invocationId, String output, String model) {
        AiToolInvocation invocation = invocations.findById(invocationId).orElseThrow();
        invocation.approvalDecision("FAILED", "AUTOMATIC_REVIEWER", model, null);
        invocation.fail(output, "REVIEW_FAILED");
    }

    @Transactional
    void toolRejected(Long invocationId, String reason, String code) {
        AiToolInvocation invocation = invocations.findById(invocationId).orElseThrow();
        invocation.reject(reason, code);
        invocation.getTurn().rejected();
        invocation.getTurn().running();
    }

    @Transactional
    void toolDenied(Long invocationId, String output, String code) {
        invocations.findById(invocationId).orElseThrow().deny(output, code);
    }

    @Transactional
    void rejectedByUser(Long invocationId, String content, String username, String subject) {
        AiToolInvocation invocation = invocations.findById(invocationId).orElseThrow();
        AiTurn turn = invocation.getTurn();
        AiConversation conversation = turn.getConversation();
        invocation.approvalDecision("REJECTED", "USER", username, subject);
        invocation.reject("Rejected by the user", "USER_REJECTED");
        invocations.findAllByTurn_IdAndStatusIn(turn.getId(), ACTIVE_TOOL_STATUSES)
                .forEach(active -> active.cancel("TURN_CANCELLED"));
        turn.rejected();
        turn.cancel();
        interruptObjective(conversation, turn.getId(), "The user rejected an action. Resume explicitly and reconcile the recorded state before further writes.");
        messages.save(new AiMessage(conversation, turn, "SYSTEM", content));
        conversation.touch();
    }

    @Transactional
    void upgraded(Long turnId, String effort) {
        turns.findById(turnId).orElseThrow().upgraded(effort);
    }

    @Transactional
    void completed(Long turnId) {
        turns.findById(turnId).orElseThrow().complete();
    }

    @Transactional
    void cancelled(Long turnId) {
        invocations.findAllByTurn_IdAndStatusIn(turnId, ACTIVE_TOOL_STATUSES)
                .forEach(invocation -> invocation.cancel("TURN_CANCELLED"));
        AiTurn turn = turns.findById(turnId).orElseThrow();
        turn.cancel();
        interruptObjective(turn.getConversation(), turnId, "The turn was cancelled. Resume explicitly and reconcile persisted actions before further writes.");
    }

    @Transactional
    void failed(Long turnId, String code, String message) {
        invocations.findAllByTurn_IdAndStatusIn(turnId, ACTIVE_TOOL_STATUSES)
                .forEach(invocation -> invocation.interrupt(code));
        AiTurn turn = turns.findById(turnId).orElseThrow();
        turn.fail(code, message);
        interruptObjective(turn.getConversation(), turnId, "The turn was interrupted. Resume explicitly and reconcile persisted actions before further writes.");
    }

    @Transactional
    void usage(Long turnId, ChatUsageUpdate value) {
        var request = value.getRequest();
        AiTokenUsageValue tokens = new AiTokenUsageValue(request.getInputTokens(), request.getCachedInputTokens(),
                request.getCacheWriteInputTokens(), request.getOutputTokens(), request.getReasoningOutputTokens(),
                request.getTotalTokens());
        List<AiRateLimitWindowValue> windows = value.getRateLimitsList().stream().map(window ->
                new AiRateLimitWindowValue(window.getKey(), window.getLabel(),
                        window.hasUsedPercent() ? window.getUsedPercent() : null,
                        window.hasRemainingPercent() ? window.getRemainingPercent() : null,
                        window.hasLimit() ? window.getLimit() : null,
                        window.hasRemaining() ? window.getRemaining() : null,
                        window.hasWindowMinutes() ? window.getWindowMinutes() : null,
                        window.hasResetsAtEpochSeconds() ? window.getResetsAtEpochSeconds() : null)).toList();
        AiQuotaSnapshotValue quota = new AiQuotaSnapshotValue(value.getPlanType(), windows, value.getObservedAtEpochSeconds());
        turns.findById(turnId).orElseThrow().recordUsage(new AiTurnUsageUpdate(tokens, value.getModelContextWindow(), quota));
    }

    @Transactional(readOnly = true)
    boolean approvalIsValid(Long invocationId, String subject) {
        return invocations.findByIdAndTurn_Conversation_UserSubject(invocationId, subject)
                .filter(value -> "PENDING_APPROVAL".equals(value.getStatus()))
                .filter(value -> value.getCreatedAt().plus(Duration.ofMinutes(15)).isAfter(Instant.now()))
                .isPresent();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    void recoverInterruptedTurns() {
        turns.findAllByStatusIn(List.of("RUNNING", "WAITING_APPROVAL")).forEach(turn -> {
            invocations.findAllByTurn_IdAndStatusIn(turn.getId(), ACTIVE_TOOL_STATUSES)
                    .forEach(invocation -> invocation.interrupt("AI_BACKEND_RESTARTED"));
            turn.fail("AI_BACKEND_RESTARTED", "The backend restarted before the turn completed");
            interruptObjective(turn.getConversation(), turn.getId(), "The backend restarted. Resume explicitly and reconcile persisted actions before further writes.");
        });
    }

    private static String title(String content) {
        String compact = content.strip().replaceAll("\\s+", " ");
        return compact.length() <= 80 ? compact : compact.substring(0, 77) + "...";
    }

    record StartedTurn(Long conversationId, Long turnId) {
    }
}
