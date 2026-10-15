package app.alertify.ai.chat;

import java.time.Instant;

/** A tool outcome reported by the backend, separate from the original tool result. */
record AiChatToolReceipt(Long invocationId, Long turnId, String toolName, boolean readOnly, String phase,
        String approvalDecision, String status, boolean failed, String diagnosticCode, Instant reportedAt,
        String timeZone, String resourceFamily, Long resourceId, String fieldComparison, boolean auditAvailable, AiChatOperationHistory.Snapshot operationAudit) {
}
