package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiToolInvocationTest {

    @Test
    void retainsTheRecordedApproverAndDateWhenAnApprovedExecutionFails() {
        AiToolInvocation invocation = new AiToolInvocation(new AiTurn(), 1L, "call", "write", "{}",
                "DOMAIN", true, true, false, false, false, false, "", true);
        assertThat(invocation.getApprovalDecision()).isEqualTo("PENDING");
        invocation.approvalDecision("APPROVED", "USER", "tester", "subject-1");
        var decidedAt = invocation.getApprovalDecidedAt();
        invocation.running("Approved by the user");
        invocation.fail("safe failure", "TEST_FAILURE");
        assertThat(invocation.getStatus()).isEqualTo("FAILED");
        assertThat(invocation.getApprovalDecision()).isEqualTo("APPROVED");
        assertThat(invocation.getApprovalActorType()).isEqualTo("USER");
        assertThat(invocation.getApprovalActor()).isEqualTo("tester");
        assertThat(invocation.getApprovalActorSubject()).isEqualTo("subject-1");
        assertThat(invocation.getApprovalDecidedAt()).isEqualTo(decidedAt).isNotNull();
    }

    @Test
    void distinguishesAutomaticRejectionFromExpiredOrCancelledApproval() {
        AiToolInvocation rejected = new AiToolInvocation(new AiTurn(), 1L, "call", "write", "{}",
                "DOMAIN", true, true, false, false, false, false, "", false);
        rejected.approvalDecision("REJECTED", "AUTOMATIC_REVIEWER", "review-model", null);
        rejected.reject("Outside requested scope", "REVIEW_REJECTED");
        assertThat(rejected.getApprovalDecision()).isEqualTo("REJECTED");
        assertThat(rejected.getApprovalActor()).isEqualTo("review-model");
        assertThat(rejected.getApprovalDecidedAt()).isNotNull();
        assertThat(rejected.getReviewReason()).isEqualTo("Outside requested scope");
        AiToolInvocation cancelled = new AiToolInvocation(new AiTurn(), 1L, "other", "write", "{}",
                "DOMAIN", true, true, false, false, false, false, "", true);
        cancelled.cancel("TURN_CANCELLED");
        assertThat(cancelled.getApprovalDecision()).isEqualTo("NOT_DECIDED");
        assertThat(cancelled.getApprovalActor()).isNull();
        assertThat(cancelled.getApprovalDecidedAt()).isNull();
        assertThat(new AiToolInvocation().getApprovalDecision()).isEqualTo("NOT_RECORDED");
    }

    @Test
    void retainsAuditClassificationAndDiagnosticForDeniedCalls() {
        AiToolInvocation invocation = new AiToolInvocation(new AiTurn(), 3L, "call-1", "missing_tool", "{}",
                "UNKNOWN", false, false, false, false, false, false, "", false);

        invocation.deny("Failed: unknown tool.", "TOOL_UNKNOWN");

        assertThat(invocation.getGeneration()).isEqualTo(3L);
        assertThat(invocation.getKind()).isEqualTo("UNKNOWN");
        assertThat(invocation.isDeclared()).isFalse();
        assertThat(invocation.isAllowed()).isFalse();
        assertThat(invocation.getStatus()).isEqualTo("REJECTED");
        assertThat(invocation.getResult()).isEqualTo("Failed: unknown tool.");
        assertThat(invocation.getDiagnosticCode()).isEqualTo("TOOL_UNKNOWN");
        assertThat(invocation.getFinishedAt()).isNotNull();
    }
}
