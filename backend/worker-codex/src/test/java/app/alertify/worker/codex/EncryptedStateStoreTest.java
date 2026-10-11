package app.alertify.worker.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedStateStoreTest {

    @TempDir
    Path directory;

    @Test
    void encryptsAndRestoresTheCompleteWorkerOwnedState() throws Exception {
        Path file = directory.resolve("state.enc");
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        EncryptedStateStore store = new EncryptedStateStore(new CodexWorkerProperties("worker", 0, 1024,
                Duration.ofSeconds(1), Duration.ofDays(30), "configured-model", "low", "ON_USE", file, key, null));
        OAuthSession session = new OAuthSession("BROWSER", "private-access-token", "private-refresh-token",
                "private-id-token", "client", "account", "user@example.test",
                Instant.parse("2026-10-10T15:00:00Z"), Instant.parse("2026-11-09T15:00:00Z"),
                Instant.parse("2026-10-10T14:00:00Z"));
        PersistentAiState expected = new PersistentAiState(new AiSettingsState(true, "CODEX", "configured-model", "low", "", "ON_USE"), session,
                Map.of("http://127.0.0.1/callback", new DynamicClient("client", "account", "User")), "urn:uuid:test-host");

        store.save(expected);

        assertThat(store.load()).isEqualTo(expected);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1))
                .doesNotContain("private-access-token", "private-refresh-token");
    }
}
