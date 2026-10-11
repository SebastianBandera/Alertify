package app.alertify.ai.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.worker.ai.grpc.ChatUsageUpdate;

@Service
class AiChatPersistence {

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
    void toolRunning(Long invocationId, String reason) {
        AiToolInvocation invocation = invocations.findById(invocationId).orElseThrow();
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
    void rejectedByUser(Long invocationId, String content) {
        AiToolInvocation invocation = invocations.findById(invocationId).orElseThrow();
        AiTurn turn = invocation.getTurn();
        AiConversation conversation = turn.getConversation();
        invocation.reject("Rejected by the user", "USER_REJECTED");
        invocations.findAllByTurn_IdAndStatusIn(turn.getId(), ACTIVE_TOOL_STATUSES)
                .forEach(active -> active.cancel("TURN_CANCELLED"));
        turn.rejected();
        turn.cancel();
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
        turns.findById(turnId).orElseThrow().cancel();
    }

    @Transactional
    void failed(Long turnId, String code, String message) {
        invocations.findAllByTurn_IdAndStatusIn(turnId, ACTIVE_TOOL_STATUSES)
                .forEach(invocation -> invocation.interrupt(code));
        turns.findById(turnId).orElseThrow().fail(code, message);
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
        });
    }

    private static String title(String content) {
        String compact = content.strip().replaceAll("\\s+", " ");
        return compact.length() <= 80 ? compact : compact.substring(0, 77) + "...";
    }

    record StartedTurn(Long conversationId, Long turnId) {
    }
}
