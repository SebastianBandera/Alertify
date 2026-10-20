package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.templates.KubernetesKubeProxyCertificateExpiryAlertTemplate.CommandResult;
import app.alertify.worker.contract.KubeconfigCredentials;

class KubernetesKubeProxyCertificateExpiryAlertTemplateTest {
    private static final Map<String, String> CERTIFICATES = new java.util.HashMap<>();
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final String CONFIG = """
            {"contexts":[{"name":"ctx","context":{"cluster":"cluster","user":"user"}}],
             "clusters":[{"name":"cluster","cluster":{"server":"https://example.test","certificate-authority-data":"Y2E="}}],
             "users":[{"name":"user","user":{"token":"fixture-token"}}]}
            """;

    @BeforeAll
    static void generateCertificates(@TempDir Path directory) throws Exception {
        Path store = directory.resolve("certificates.p12");
        for (String name : List.of("leaf", "longleaf", "ca", "other")) {
            boolean ca = "ca".equals(name);
            String subject = ca ? "fixture-ca" : "other".equals(name) ? "system:other" : "system:kube-proxy";
            String validity = ca ? "3650" : "longleaf".equals(name) ? "730" : "365";
            keytool("-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + subject,
                    "-validity", validity, "-startdate", "2026/01/01 00:00:00", "-keystore", store.toString(),
                    "-storepass", "fixture-password", "-ext", "BC=ca:" + ca);
            Path certificate = directory.resolve(name + ".pem");
            keytool("-exportcert", "-rfc", "-alias", name, "-keystore", store.toString(), "-storepass", "fixture-password", "-file", certificate.toString());
            CERTIFICATES.put(name, Files.readString(certificate, StandardCharsets.UTF_8));
        }
    }

    private static void keytool(String... arguments) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-J-Duser.timezone=UTC"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Test certificate generation timed out");
            assertEquals(0, process.exitValue(), "Test certificate generation failed");
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void succeedsOnlyWhenAllNodesHaveHealthyCertificatesAndLeavesStateEmpty() throws Exception {
        FakeSession session = session(3, ok(leaf()), ok(leaf()), ok(leaf()));
        AlertExecutionContext context = new AlertExecutionContext();
        AlertResult result = template(session, NOW, 30).evaluate(context);

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals(3, result.statusMessage().get("totalNodes"));
        assertEquals(3L, result.statusMessage().get("healthyNodes"));
        assertEquals(0L, result.statusMessage().get("warningNodes"));
        assertTrue(context.getState() == null || context.getState().isEmpty());
        assertTrue(session.closed);
        assertEquals(6, session.commands.size());
        String output = result.statusMessage().toString();
        assertFalse(output.contains("BEGIN CERTIFICATE"));
        assertFalse(output.contains("fixture-token"));
    }

    @Test
    void warnsWhenJustOneNodeFailsAndStillChecksTheOtherNodes() throws Exception {
        FakeSession session = session(3, ok(leaf()), failed("kubernetesRbacDenied"), ok(leaf()));
        AlertResult result = template(session, NOW, 30).evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals(2L, result.statusMessage().get("healthyNodes"));
        assertEquals(1L, result.statusMessage().get("warningNodes"));
        assertEquals("kubernetesRbacDenied", nodes(result).get(1).get("reason"));
        assertEquals("SUCCESS", nodes(result).get(2).get("status"));
        assertEquals("node-2", nodes(result).get(1).get("node"));
        assertEquals("proxy-node-2", nodes(result).get(1).get("pod"));
    }

    @Test
    void oneExpiringCertificateMakesTheClusterWarnWhileOtherNodesRemainHealthy() throws Exception {
        FakeSession session = session(3, ok(fixture("longleaf")), ok(leaf()), ok(fixture("longleaf")));
        AlertResult result = template(session, Instant.parse("2026-12-20T00:00:00Z"), 30).evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals(1L, result.statusMessage().get("warningNodes"));
        assertEquals(2L, result.statusMessage().get("healthyNodes"));
        assertEquals("kubeProxyCertificateExpiring", nodes(result).get(1).get("reason"));
        assertEquals(12L, nodes(result).get(1).get("daysRemaining"));
    }

    @Test
    void usesTheSameDayCalculationAndInclusiveThresholdAsHttps() throws Exception {
        Instant expiry = Instant.parse("2027-01-01T00:00:00Z");
        AlertResult threshold = template(session(1, ok(leaf())), expiry.minusSeconds(30 * 86400L), 30).evaluate(new AlertExecutionContext());
        assertEquals("kubeProxyCertificateExpiring", nodes(threshold).getFirst().get("reason"));
        assertEquals(30L, nodes(threshold).getFirst().get("daysRemaining"));

        AlertResult healthy = template(session(1, ok(leaf())), expiry.minusSeconds(31 * 86400L), 30).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.SUCCESS, healthy.status());

        AlertResult expired = template(session(1, ok(leaf())), expiry.plusSeconds(1), 30).evaluate(new AlertExecutionContext());
        assertEquals("kubeProxyCertificateExpired", nodes(expired).getFirst().get("reason"));

        AlertResult future = template(session(1, ok(leaf())), Instant.parse("2025-12-31T00:00:00Z"), 30).evaluate(new AlertExecutionContext());
        assertEquals("kubeProxyCertificateNotYetValid", nodes(future).getFirst().get("reason"));
    }

    @Test
    void readsTheLeafInACertificateBundleAndRejectsUnexpectedOrAmbiguousCertificates() throws Exception {
        AlertResult bundle = template(session(1, ok(fixture("ca") + leaf())), NOW, 30).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.SUCCESS, bundle.status());

        for (String pem : List.of("not a certificate", fixture("other"), fixture("ca"), leaf() + leaf())) {
            AlertResult result = template(session(1, ok(pem)), NOW, 30).evaluate(new AlertExecutionContext());
            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals("kubeProxyCertificateInvalid", nodes(result).getFirst().get("reason"));
        }
    }

    @Test
    void passesCustomSelectorAndContainerAsLiteralArgumentsAndKeepsTheReadCommandFixed() throws Exception {
        String selector = "component in (proxy-v2,proxy-v3),!retired";
        FakeSession session = new FakeSession(ok(CONFIG), ok(nodeList(1)), ok(podList(pod("node-1", "proxy-agent"))), ok(leaf()));
        var template = new KubernetesKubeProxyCertificateExpiryAlertTemplate(credentials(), "custom context", selector, "proxy-agent", 30, 10,
                (_, context, timeout) -> {
                    assertEquals("custom context", context);
                    assertEquals(10, timeout);
                    return session;
                }, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(AlertExecutionStatus.SUCCESS, template.evaluate(new AlertExecutionContext()).status());
        assertEquals(List.of("get", "pods", "--namespace=kube-system", "--selector=" + selector, "-o=json"), session.commands.get(2));
        assertEquals(List.of("exec", "--namespace=kube-system", "proxy-node-1", "--container=proxy-agent", "--", "cat",
                "/var/lib/rancher/rke2/agent/client-kube-proxy.crt"), session.commands.get(3));
    }

    @Test
    void missingAmbiguousAndNonRunningPodsWarnWithoutExecutingInTheWrongPod() throws Exception {
        String valid = pod("node-1", "kube-proxy");
        for (String pods : List.of("", valid + "," + valid, valid.replace("kube-proxy", "other"),
                valid.replace("\"running\":{}", "\"waiting\":{}"), valid.replace("\"Running\"", "\"Failed\""),
                valid.replace("\"namespace\":\"kube-system\"", "\"namespace\":\"other\""),
                valid.replace("\"namespace\":\"kube-system\"", "\"namespace\":\"kube-system\",\"deletionTimestamp\":\"2026-10-15T00:00:00Z\""))) {
            FakeSession session = new FakeSession(ok(CONFIG), ok(nodeList(1)), ok(podList(pods)));
            AlertResult result = template(session, NOW, 30).evaluate(new AlertExecutionContext());
            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals(1L, result.statusMessage().get("warningNodes"));
            assertEquals(3, session.commands.size());
        }
    }

    @Test
    void queryFailuresMalformedInventoriesAndAnEmptyClusterNeverSucceed() throws Exception {
        for (CommandResult query : List.of(failed("kubernetesRbacDenied"), failed("kubernetesAuthenticationFailed"),
                new CommandResult(0, new byte[0], null, true, false), ok("not json"), ok("{}"), ok(nodeList(0)))) {
            FakeSession session = new FakeSession(ok(CONFIG), query);
            assertEquals(AlertExecutionStatus.WARN, template(session, NOW, 30).evaluate(new AlertExecutionContext()).status());
        }
        FakeSession podFailure = new FakeSession(ok(CONFIG), ok(nodeList(1)), failed("kubernetesConnectionFailed"));
        AlertResult failed = template(podFailure, NOW, 30).evaluate(new AlertExecutionContext());
        assertEquals("kubernetesConnectionFailed", failed.statusMessage().get("reason"));
        assertEquals(1, failed.statusMessage().get("totalNodes"));
        assertEquals(1L, failed.statusMessage().get("warningNodes"));
    }

    @Test
    void timeoutsOutputLimitsAndReadFailuresDoNotAbortSubsequentNodes() throws Exception {
        for (Object failure : List.of(new CommandResult(-1, new byte[0], null, true, false),
                new CommandResult(-1, new byte[0], null, false, true), failed(null), new IOException("fixture-token: confidential details"))) {
            FakeSession session = session(2, failure, ok(leaf()));
            AlertResult result = template(session, NOW, 30).evaluate(new AlertExecutionContext());
            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals("SUCCESS", nodes(result).get(1).get("status"));
            assertFalse(result.statusMessage().toString().contains("fixture-token"));
        }
    }

    @Test
    void blocksUnsafeKubeconfigProvidersAndLocalPathsBeforeQueryingNodes() throws Exception {
        for (String field : List.of("\"exec\":{}", "\"auth-provider\":{}", "\"tokenFile\":\"/tmp/token\"",
                "\"client-key\":\"/tmp/key\"", "\"client-certificate\":\"/tmp/cert\"")) {
            FakeSession session = new FakeSession(ok(CONFIG.replace("\"token\":\"fixture-token\"", field)));
            AlertResult result = template(session, NOW, 30).evaluate(new AlertExecutionContext());
            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals(1, session.commands.size());
            assertTrue(session.closed);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "UPPER", "-option", "proxy;cat", "proxy name" })
    void rejectsInvalidContainerNamesBeforeOpeningASession(String container) {
        var template = new KubernetesKubeProxyCertificateExpiryAlertTemplate(credentials(), "", "component=kube-proxy", container, 30, 10,
                (_, _, _) -> { throw new AssertionError("must not open a session"); }, Clock.systemUTC());
        assertThrows(IllegalArgumentException.class, () -> template.evaluate(new AlertExecutionContext()));
    }

    @Test
    void rejectsBlankSelectorsNegativeDaysAndInvalidTimeouts() {
        for (String selector : List.of("", " ", "a\0b")) {
            var template = new KubernetesKubeProxyCertificateExpiryAlertTemplate(credentials(), "", selector, "kube-proxy", 30, 10);
            assertThrows(IllegalArgumentException.class, () -> template.evaluate(new AlertExecutionContext()));
        }
        assertThrows(IllegalArgumentException.class, () -> template(new FakeSession(), NOW, -1).evaluate(new AlertExecutionContext()));
        var invalidTimeout = new KubernetesKubeProxyCertificateExpiryAlertTemplate(credentials(), "", "component=kube-proxy", "kube-proxy", 30, 1);
        assertThrows(IllegalArgumentException.class, () -> invalidTimeout.evaluate(new AlertExecutionContext()));
    }

    @Test
    void copiesWarningDaysMetadataAndExposesBindingsForSelectorAndContainer() throws Exception {
        AlertParameter expected = HttpsCertificateExpiryAlertTemplate.class.getDeclaredField("warningDays").getAnnotation(AlertParameter.class);
        AlertParameter actual = KubernetesKubeProxyCertificateExpiryAlertTemplate.class.getDeclaredField("warningDays").getAnnotation(AlertParameter.class);
        assertEquals(expected.labelKey(), actual.labelKey());
        assertEquals(expected.descriptionKey(), actual.descriptionKey());
        assertEquals(expected.defaultValue(), actual.defaultValue());
        assertArrayEquals(expected.options(), actual.options());
        assertEquals(expected.bindingAllowed(), actual.bindingAllowed());
        for (String name : List.of("podSelector", "container")) {
            AlertParameter parameter = KubernetesKubeProxyCertificateExpiryAlertTemplate.class.getDeclaredField(name).getAnnotation(AlertParameter.class);
            assertTrue(parameter.bindingAllowed());
            assertArrayEquals(new AlertParameterSource[] { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION }, parameter.allowedSources());
        }
    }

    private static KubernetesKubeProxyCertificateExpiryAlertTemplate template(FakeSession session, Instant now, int days) {
        return new KubernetesKubeProxyCertificateExpiryAlertTemplate(credentials(), "", "component=kube-proxy", "kube-proxy", days, 10,
                (_, context, timeout) -> session, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static KubeconfigCredentials credentials() {
        return new KubeconfigCredentials("apiVersion: v1\n");
    }

    private static FakeSession session(int nodes, Object... reads) {
        List<Object> replies = new ArrayList<>(List.of(ok(CONFIG), ok(nodeList(nodes))));
        List<String> pods = new ArrayList<>();
        for (int index = 1; index <= nodes; index++)
            pods.add(pod("node-" + index, "kube-proxy"));

        replies.add(ok(podList(String.join(",", pods))));
        replies.addAll(List.of(reads));
        return new FakeSession(replies.toArray());
    }

    private static String nodeList(int count) {
        List<String> nodes = new ArrayList<>();
        for (int index = 1; index <= count; index++)
            nodes.add("{\"metadata\":{\"name\":\"node-" + index + "\"}}");

        return "{\"items\":[" + String.join(",", nodes) + "]}";
    }

    private static String podList(String pods) {
        return "{\"items\":[" + pods + "]}";
    }

    private static String pod(String node, String container) {
        return """
                {"metadata":{"name":"proxy-%s","namespace":"kube-system"},
                 "spec":{"nodeName":"%s","containers":[{"name":"%s"}]},
                 "status":{"phase":"Running","containerStatuses":[{"name":"%s","state":{"running":{}}}]}}
                """.formatted(node, node, container, container);
    }

    private static String leaf() throws IOException {
        return fixture("leaf");
    }

    private static String fixture(String name) throws IOException {
        return CERTIFICATES.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(AlertResult result) {
        return (List<Map<String, Object>>) result.statusMessage().get("nodes");
    }

    private static CommandResult ok(String value) {
        return new CommandResult(0, value.getBytes(StandardCharsets.UTF_8), null, false, false);
    }

    private static CommandResult failed(String reason) {
        return new CommandResult(1, new byte[0], reason, false, false);
    }

    private static final class FakeSession implements KubernetesKubeProxyCertificateExpiryAlertTemplate.KubectlSession {
        private final ArrayDeque<Object> replies;
        private final List<List<String>> commands = new ArrayList<>();
        private boolean sanitized;
        private boolean closed;

        FakeSession(Object... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public CommandResult run(List<String> arguments) throws Exception {
            if (!commands.isEmpty())
                assertTrue(sanitized, "queries must follow kubeconfig sanitization");

            commands.add(List.copyOf(arguments));
            Object reply = replies.removeFirst();
            if (reply instanceof Exception exception)
                throw exception;

            return (CommandResult) reply;
        }

        @Override
        public void replaceKubeconfig(byte[] value) {
            sanitized = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
