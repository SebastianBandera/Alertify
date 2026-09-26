package app.alertify.worker.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.alertify.worker.grpc.ExecutionError;

class ExecutionErrorSanitizerTest {

    @Test
    void redactsPlainAndStructuredSecretValuesFromMessageAndStackTrace() {
        String structured = "{\"host\":\"db.internal\",\"username\":\"monitor\",\"password\":\"opaque-password\"}";
        ExecutionError error = ExecutionError.newBuilder()
                .setType("example.Failure")
                .setMessage("token=plain-token password=opaque-password host=db.internal")
                .setStackTrace("request by monitor used " + structured + " and plain-token")
                .build();

        ExecutionError sanitized = ExecutionErrorSanitizer.sanitize(error, List.of("plain-token", structured));

        assertThat(sanitized.getType()).isEqualTo("example.Failure");
        assertThat(sanitized.getMessage()).isEqualTo("token=[REDACTED] password=[REDACTED] host=[REDACTED]");
        assertThat(sanitized.getStackTrace()).doesNotContain("monitor", "opaque-password", "plain-token", structured);
        assertThat(sanitized.getStackTrace()).contains("request by [REDACTED] used [REDACTED] and [REDACTED]");
    }

    @Test
    void leavesDiagnosticsUnchangedWithoutUsableSecretValues() {
        ExecutionError error = ExecutionError.newBuilder()
                .setType("example.Failure")
                .setMessage("safe message")
                .setStackTrace("safe stack")
                .build();

        assertThat(ExecutionErrorSanitizer.sanitize(error, Arrays.asList("", null))).isEqualTo(error);
    }
}
