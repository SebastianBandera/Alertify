package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.alertify.worker.contract.KubeconfigCredentials;

class KubernetesWorkloadProcessTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void createsRestrictedFilesUsesDenyAllIsolatesHomeAndCleansUp() throws Exception {
        Path executable = script("""
                #!/bin/sh
                for arg in "$@"; do
                  case "$arg" in
                    --kubeconfig=*) kubeconfig="${arg#*=}" ;;
                    --kuberc=*) kuberc="${arg#*=}" ;;
                  esac
                done
                /bin/grep -q 'credentialPluginPolicy: DenyAll' "$kuberc" || exit 8
                test "$KUBECONFIG" = "$kubeconfig" || exit 9
                test "$KUBERC" = "$kuberc" || exit 10
                printf '{"home":"%s"}' "$HOME"
                """);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        KubernetesWorkloadAlertTemplate.ProcessKubectlSession session =
                KubernetesWorkloadAlertTemplate.ProcessKubectlSession.open(credentials(), "ctx exact", deadline, executable);
        Path directory = session.directory();

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(session.home())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(session.kubeconfig())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(session.kuberc())));
        assertEquals("apiVersion: v1\nsecret-token-value\n", Files.readString(session.kubeconfig(), StandardCharsets.UTF_8));

        KubernetesWorkloadAlertTemplate.CommandResult result = session.run(List.of("config", "view"));
        assertTrue(result.success());
        String output = new String(result.stdout(), StandardCharsets.UTF_8);
        assertTrue(output.contains(session.home().toString()));
        assertFalse(result.toString().contains("secret-token-value"));

        session.close();
        assertFalse(Files.exists(directory));
    }

    @Test
    void appliesOneDeadlineAndDecreasingRequestTimeoutToEveryProcess() throws Exception {
        Path executable = script("""
                #!/bin/sh
                /bin/sleep 0.15
                printf '%s\n' "$@"
                """);
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        try (var session = KubernetesWorkloadAlertTemplate.ProcessKubectlSession.open(credentials(), null, deadline, executable)) {
            long first = requestTimeout(session.run(List.of("get", "one")));
            long second = requestTimeout(session.run(List.of("get", "two")));
            assertTrue(first > second);
            assertTrue(first <= 2000 && second > 0);
        }
    }

    @Test
    void atomicallyReplacesTheOriginalKubeconfigWithRestrictedPermissions() throws Exception {
        Path executable = script("""
                #!/bin/sh
                exit 0
                """);
        try (var session = KubernetesWorkloadAlertTemplate.ProcessKubectlSession.open(
                credentials(), null, System.nanoTime() + Duration.ofSeconds(5).toNanos(), executable)) {
            byte[] sanitized = "{\"apiVersion\":\"v1\",\"kind\":\"Config\"}".getBytes(StandardCharsets.UTF_8);

            session.replaceKubeconfig(sanitized);

            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(session.kubeconfig())));
            assertEquals(new String(sanitized, StandardCharsets.UTF_8), Files.readString(session.kubeconfig(), StandardCharsets.UTF_8));
            assertFalse(Files.readString(session.kubeconfig(), StandardCharsets.UTF_8).contains("secret-token-value"));
        }
    }

    @Test
    void cleanupDeletesSymlinksWithoutFollowingTheirTargets() throws Exception {
        Path executable = script("""
                #!/bin/sh
                exit 0
                """);
        Path externalDirectory = Files.createDirectory(temporaryDirectory.resolve("external"));
        Path externalMarker = Files.writeString(externalDirectory.resolve("marker"), "must remain", StandardCharsets.UTF_8);
        Path missingTarget = temporaryDirectory.resolve("missing");
        var session = KubernetesWorkloadAlertTemplate.ProcessKubectlSession.open(
                credentials(), null, System.nanoTime() + Duration.ofSeconds(5).toNanos(), executable);
        Path sessionDirectory = session.directory();
        Files.createSymbolicLink(sessionDirectory.resolve("external-link"), externalDirectory);
        Files.createSymbolicLink(sessionDirectory.resolve("broken-link"), missingTarget);

        session.close();

        assertFalse(Files.exists(sessionDirectory, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(externalDirectory));
        assertEquals("must remain", Files.readString(externalMarker, StandardCharsets.UTF_8));
    }

    @Test
    void drainsBothStreamsAndStopsAtTheCombinedTenMibLimit() throws Exception {
        Path executable = script("""
                #!/bin/sh
                /usr/bin/head -c 6291456 /dev/zero &
                /usr/bin/head -c 6291456 /dev/zero >&2 &
                wait
                """);
        try (var session = KubernetesWorkloadAlertTemplate.ProcessKubectlSession.open(
                credentials(), null, System.nanoTime() + Duration.ofSeconds(5).toNanos(), executable)) {
            KubernetesWorkloadAlertTemplate.CommandResult result = session.run(List.of("get", "pods"));
            assertTrue(result.outputLimitExceeded());
            assertFalse(result.success());
        }
    }

    @Test
    void destroysTheProcessAtTheGlobalDeadlineAndOnInterruption() throws Exception {
        Path executable = script("""
                #!/bin/sh
                /bin/sleep 30
                """);
        long started = System.nanoTime();
        try (var session = KubernetesWorkloadAlertTemplate.ProcessKubectlSession.open(
                credentials(), null, System.nanoTime() + Duration.ofMillis(150).toNanos(), executable)) {
            KubernetesWorkloadAlertTemplate.CommandResult result = session.run(List.of("get", "pods"));
            assertTrue(result.timedOut());
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);

        try (var session = KubernetesWorkloadAlertTemplate.ProcessKubectlSession.open(
                credentials(), null, System.nanoTime() + Duration.ofSeconds(10).toNanos(), executable)) {
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    session.run(List.of("get", "pods"));
                    outcome.set(new AssertionError("run returned after interruption"));
                } catch (Throwable exception) {
                    outcome.set(exception);
                }
            });
            Thread.sleep(100);
            thread.interrupt();
            thread.join(2000);
            assertFalse(thread.isAlive());
            assertTrue(outcome.get() instanceof InterruptedException, String.valueOf(outcome.get()));
        }
    }

    private Path script(String content) throws Exception {
        Path path = temporaryDirectory.resolve("kubectl-" + System.nanoTime());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }

    private static KubeconfigCredentials credentials() {
        return new KubeconfigCredentials("apiVersion: v1\nsecret-token-value\n");
    }

    private static long requestTimeout(KubernetesWorkloadAlertTemplate.CommandResult result) {
        assertTrue(result.success());
        return new String(result.stdout(), StandardCharsets.UTF_8).lines()
                .filter(value -> value.startsWith("--request-timeout="))
                .map(value -> value.substring("--request-timeout=".length(), value.length() - 2))
                .mapToLong(Long::parseLong)
                .findFirst().orElseThrow();
    }
}
