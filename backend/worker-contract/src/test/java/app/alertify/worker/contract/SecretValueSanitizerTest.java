package app.alertify.worker.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

import org.junit.jupiter.api.Test;

import app.alertify.worker.grpc.ExecutionError;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SecretValueSanitizerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void protectsBase64AlphabetsPaddingCharsetsAndArbitraryMimeFolding() {
        for (String value : List.of("canary-contraseña-🔐", "canary-credential-with-long-content-".repeat(5), "ab", "abc", "abcd")) {
            var protection = SecretValueSanitizer.protection(List.of(value));
            for (var charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
                for (var encoder : List.of(Base64.getEncoder(), Base64.getUrlEncoder())) {
                    String encoded = encoder.encodeToString(value.getBytes(charset));
                    for (String representation : List.of(encoded, encoded.replaceFirst("=+$", ""), String.join("\r\n", encoded.split("(?<=\\G.{37})")))) {
                        assertThat(protection.sanitize("before:" + representation + ":after")).isEqualTo("before:[REDACTED]:after");
                        assertThat(protection.sanitize(JSON.valueToTree(Map.of("detail", representation))).path("detail").asString()).isEqualTo("[REDACTED]");
                        assertThat(protection.sanitize(URLEncoder.encode(representation, StandardCharsets.UTF_8))).isEqualTo("[REDACTED]");
                        assertThat(protection.sanitize(representation.replace("\r", "\\r").replace("\n", "\\n"))).isEqualTo("[REDACTED]");
                    }
                }
            }
            assertThat(protection.sanitize("Public page content")).isEqualTo("Public page content");
        }
    }

    @Test
    void protectsBase64FieldsOfStructuredSecretsWithoutChangingDiagnosticNumbers() {
        String value = "fixture-credential";
        String encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        JsonNode result = SecretValueSanitizer.sanitize(JSON.valueToTree(Map.of("detail", encoded, "completed", 4)), List.of(JSON.writeValueAsString(Map.of("password", value))));
        assertThat(result.path("detail").asString()).isEqualTo("[REDACTED]");
        assertThat(result.path("completed").asInt()).isEqualTo(4);
    }

    @Test
    void redactsPlainAndStructuredSecretValuesFromMessageAndStackTrace() {
        String structured = "{\"host\":\"db.internal\",\"username\":\"monitor\",\"password\":\"opaque-password\"}";
        ExecutionError error = ExecutionError.newBuilder()
                .setType("example.Failure")
                .setMessage("token=plain-token password=opaque-password host=db.internal")
                .setStackTrace("request by monitor used " + structured + " and plain-token")
                .build();

        ExecutionError sanitized = SecretValueSanitizer.sanitize(error, List.of("plain-token", structured));

        assertThat(sanitized.getType()).isEqualTo("example.Failure");
        assertThat(sanitized.getMessage()).isEqualTo("token=[REDACTED] password=[REDACTED] host=[REDACTED]");
        assertThat(sanitized.getStackTrace()).doesNotContain("monitor", "opaque-password", "plain-token", structured);
        assertThat(sanitized.getStackTrace()).contains("request by [REDACTED] used [REDACTED] and [REDACTED]");
    }

    @Test
    void redactsJsonTextNodesAfterEscapesHaveBeenDecoded() {
        String escapedSecret = "pa\"ss\\word";
        String structured = JSON.writeValueAsString(Map.of(
                "host", "db.internal",
                "password", escapedSecret
        ));
        JsonNode message = JSON.valueToTree(Map.of(
                "detail", "Authentication with " + escapedSecret + " failed",
                "nested", List.of("db.internal", "safe"),
                "attempts", 3
        ));

        JsonNode sanitized = SecretValueSanitizer.sanitize(message, List.of(structured));

        assertThat(sanitized.get("detail").stringValue()).isEqualTo("Authentication with [REDACTED] failed");
        assertThat(sanitized.get("nested").get(0).stringValue()).isEqualTo("[REDACTED]");
        assertThat(sanitized.get("nested").get(1).stringValue()).isEqualTo("safe");
        assertThat(sanitized.get("attempts").intValue()).isEqualTo(3);
        assertThat(message.get("detail").stringValue()).contains(escapedSecret);
    }

    @Test
    void redactsTokensExtractedFromAuthorizationHeaders() {
        String headers = "[\"Accept: application/json\",\"Authorization: Bearer opaque.jwt.token\",\"Basic ZHVtbXk6c2VjcmV0\"]";
        ExecutionError error = ExecutionError.newBuilder()
                .setType("example.Failure")
                .setMessage("Bearer token opaque.jwt.token was rejected")
                .setStackTrace("authorization=ZHVtbXk6c2VjcmV0")
                .build();

        ExecutionError sanitized = SecretValueSanitizer.sanitize(error, List.of(headers));

        assertThat(sanitized.getMessage()).isEqualTo("Bearer token [REDACTED] was rejected");
        assertThat(sanitized.getStackTrace()).isEqualTo("authorization=[REDACTED]");
    }

    @Test
    void leavesOutputsUnchangedWithoutUsableSecretValues() {
        ExecutionError error = ExecutionError.newBuilder()
                .setType("example.Failure")
                .setMessage("safe message")
                .setStackTrace("safe stack")
                .build();
        JsonNode message = JSON.valueToTree(Map.of("message", "safe"));

        assertThat(SecretValueSanitizer.sanitize(error, Arrays.asList("", null))).isEqualTo(error);
        assertThat(SecretValueSanitizer.sanitize("safe state", Arrays.asList("", null))).isEqualTo("safe state");
        assertThat(SecretValueSanitizer.sanitize(message, Arrays.asList("", null))).isSameAs(message);
    }
}
