package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiToolInvocationTest {

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
