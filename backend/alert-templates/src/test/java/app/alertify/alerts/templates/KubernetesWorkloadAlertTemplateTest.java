package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.worker.contract.KubeconfigCredentials;
import app.alertify.alerts.templates.KubernetesWorkloadAlertTemplate.KubernetesWorkloadKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class KubernetesWorkloadAlertTemplateTest {

    private static final String UID = "workload-uid";
    private static final String CONFIG = """
            {"current-context":"ctx","contexts":[{"name":"ctx","context":{"cluster":"cluster","user":"user"}}],
             "clusters":[{"name":"cluster","cluster":{"server":"https://EXAMPLE.test:443","certificate-authority-data":"Y2E="}}],
             "users":[{"name":"user","user":{"token":"redacted"}}]}
            """;

    @Test
    void deploymentHealthAndRestartBaselineLifecycle() throws Exception {
        AlertExecutionContext context = new AlertExecutionContext();
        FakeSession first = deploymentSession(deployment(3, 4, 3, 3, 3, 0), pods(2, UID, "rs-uid"));
        AlertResult initial = template(KubernetesWorkloadKind.Deployment, true, first).evaluate(context);
        assertEquals(AlertExecutionStatus.WARN, initial.status());
        assertEquals(Boolean.TRUE, initial.statusMessage().get("restartTrackingEnabled"));
        assertEquals(2L, initial.statusMessage().get("restartTotal"));
        assertEquals(2L, initial.statusMessage().get("restartDelta"));

        FakeSession stableSession = deploymentSession(deployment(3, 4, 3, 3, 3, 0), pods(2, UID, "rs-uid"));
        AlertResult stable = template(KubernetesWorkloadKind.Deployment, true, stableSession).evaluate(context);
        assertEquals(AlertExecutionStatus.SUCCESS, stable.status());
        assertEquals(0L, stable.statusMessage().get("restartDelta"));

        FakeSession increasedSession = deploymentSession(deployment(3, 4, 3, 3, 3, 0), pods(5, UID, "rs-uid"));
        AlertResult increased = template(KubernetesWorkloadKind.Deployment, true, increasedSession).evaluate(context);
        assertEquals(AlertExecutionStatus.WARN, increased.status());
        assertEquals(3L, increased.statusMessage().get("restartDelta"));

        FakeSession decreasedSession = deploymentSession(deployment(3, 4, 3, 3, 3, 0), pods(1, UID, "rs-uid"));
        AlertResult decreased = template(KubernetesWorkloadKind.Deployment, true, decreasedSession).evaluate(context);
        assertEquals(AlertExecutionStatus.SUCCESS, decreased.status());

        FakeSession rotatedSession = deploymentSession(deployment(3, 4, 3, 3, 3, 0), pods(7, "new-pod-uid", "rs-uid"));
        AlertResult rotated = template(KubernetesWorkloadKind.Deployment, true, rotatedSession).evaluate(context);
        assertEquals(AlertExecutionStatus.SUCCESS, rotated.status());
    }

    @Test
    void reportsDegradedAndLaggingWorkloadsIncludingDaemonMisscheduling() throws Exception {
        FakeSession deployment = deploymentSession(deployment(3, 2, 2, 1, 1, 2), pods(0, UID, "rs-uid"));
        AlertResult deploymentResult = template(KubernetesWorkloadKind.Deployment, true, deployment).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.WARN, deploymentResult.status());
        assertTrue(reasons(deploymentResult).contains("generationNotObserved"));

        FakeSession stateful = baseSession(statefulSet(0, 0, 0, 0, "rev-a", "rev-b"), podsList("[]"));
        AlertResult statefulResult = template(KubernetesWorkloadKind.StatefulSet, true, stateful).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.WARN, statefulResult.status());
        assertTrue(reasons(statefulResult).contains("revisionMismatch"));

        FakeSession daemon = baseSession(daemonSet(2, 2, 2, 2, 2, 0, 1), podsList("[]"));
        AlertResult daemonResult = template(KubernetesWorkloadKind.DaemonSet, true, daemon).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.WARN, daemonResult.status());
        assertTrue(reasons(daemonResult).contains("misscheduledNodesPresent"));
    }

    @Test
    void treatsObservedZeroScaleAsHealthy() throws Exception {
        FakeSession deployment = deploymentSession(deployment(0, 4, 0, 0, 0, 0), podsList("[]"));
        AlertResult result = template(KubernetesWorkloadKind.Deployment, true, deployment).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
    }

    @Test
    void rejectsInvalidKubernetesNamespaceAndWorkloadNames() {
        for (String invalid : List.of("a..b", "a.-b", "a-.b", "UPPER", "a".repeat(64) + ".b")) {
            var template = new KubernetesWorkloadAlertTemplate(new KubeconfigCredentials("x"), "", "default",
                    KubernetesWorkloadKind.Deployment, invalid, false, 10, (_, _, _) -> new FakeSession());
            assertThrows(IllegalArgumentException.class, () -> template.evaluate(new AlertExecutionContext()));
        }
        var invalidNamespace = new KubernetesWorkloadAlertTemplate(new KubeconfigCredentials("x"), "", "not.valid",
                KubernetesWorkloadKind.Deployment, "valid", false, 10, (_, _, _) -> new FakeSession());
        assertThrows(IllegalArgumentException.class, () -> invalidNamespace.evaluate(new AlertExecutionContext()));
    }

    @Test
    void acceptsHealthyStatefulSetAndDaemonSetAndRequiresDirectControllerUid() throws Exception {
        AlertResult stateful = template(KubernetesWorkloadKind.StatefulSet, true,
                baseSession(statefulSet(2, 4, 2, 2, "rev", "rev"), pods(0, "pod", UID))).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.SUCCESS, stateful.status());
        assertTrue(reasons(stateful).isEmpty());

        String ownedAndForeign = "[{\"metadata\":{\"uid\":\"owned\",\"name\":\"owned\",\"ownerReferences\":[{\"uid\":\"" + UID + "\",\"controller\":true}]},\"status\":{\"containerStatuses\":[{\"name\":\"main\",\"restartCount\":1}]}},{\"metadata\":{\"uid\":\"foreign\",\"name\":\"foreign\",\"ownerReferences\":[{\"uid\":\"wrong\",\"controller\":true}]},\"status\":{\"containerStatuses\":[{\"name\":\"main\",\"restartCount\":99}]}}]";
        AlertResult daemon = template(KubernetesWorkloadKind.DaemonSet, true,
                baseSession(daemonSet(2, 2, 2, 2, 2, 0, 0), podsList("[]"))).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.SUCCESS, daemon.status());
        assertTrue(reasons(daemon).isEmpty());

        AlertResult ownership = template(KubernetesWorkloadKind.DaemonSet, true,
                baseSession(daemonSet(2, 2, 2, 2, 2, 0, 0), podsList(ownedAndForeign))).evaluate(new AlertExecutionContext());
        assertEquals(1L, ownership.statusMessage().get("restartTotal"));
    }

    @Test
    void treatsZeroScaleStatefulSetAndZeroDesiredDaemonSetAsHealthy() throws Exception {
        String zeroStateful = statefulSet(0, 4, 0, 0, "rev", "rev")
                .replace(",\"currentRevision\":\"rev\",\"updateRevision\":\"rev\"", "");
        AlertResult stateful = template(KubernetesWorkloadKind.StatefulSet, true,
                baseSession(zeroStateful, podsList("[]"))).evaluate(new AlertExecutionContext());
        AlertResult daemon = template(KubernetesWorkloadKind.DaemonSet, true,
                baseSession(daemonSet(0, 0, 0, 0, 0, 0, 0), podsList("[]"))).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.SUCCESS, stateful.status());
        assertEquals(AlertExecutionStatus.SUCCESS, daemon.status());
    }

    @Test
    void buildsAllSelectorExpressionsAndFiltersOwnersByUid() throws Exception {
        String workload = deployment(1, 4, 1, 1, 1, 0).replace(
                "\"selector\":{\"matchLabels\":{\"app\":\"demo\"}}",
                "\"selector\":{\"matchLabels\":{\"tier\":\"api\",\"app\":\"demo\"},\"matchExpressions\":["
                        + "{\"key\":\"track\",\"operator\":\"In\",\"values\":[\"stable\",\"canary\"]},"
                        + "{\"key\":\"zone\",\"operator\":\"NotIn\",\"values\":[\"legacy\"]},"
                        + "{\"key\":\"managed\",\"operator\":\"Exists\"},"
                        + "{\"key\":\"retired\",\"operator\":\"DoesNotExist\"}]}"
        );
        String replicaSets = """
                {"items":[
                  {"metadata":{"uid":"owned-rs","ownerReferences":[{"uid":"workload-uid","controller":true}]}},
                  {"metadata":{"uid":"foreign-rs","ownerReferences":[{"uid":"other","controller":true}]}}
                ]}
                """;
        String podItems = """
                [{"metadata":{"uid":"owned-pod","name":"owned","ownerReferences":[{"uid":"owned-rs","controller":true}]},"status":{"containerStatuses":[{"name":"main","restartCount":1}]}},
                 {"metadata":{"uid":"foreign-pod","name":"foreign","ownerReferences":[{"uid":"foreign-rs","controller":true}]},"status":{"containerStatuses":[{"name":"main","restartCount":99}]}},
                 {"metadata":{"uid":"label-only","name":"label-only"},"status":{"containerStatuses":[{"name":"main","restartCount":99}]}}]
                """;
        FakeSession session = new FakeSession(ok(CONFIG), ok(workload), ok(replicaSets), ok(podsList(podItems)));
        AlertResult result = template(KubernetesWorkloadKind.Deployment, true, session).evaluate(new AlertExecutionContext());

        assertEquals(1L, result.statusMessage().get("restartTotal"));
        String selectorArgument = session.commands.get(2).stream().filter(value -> value.startsWith("--selector=")).findFirst().orElseThrow();
        assertTrue(selectorArgument.contains("app=demo"));
        assertTrue(selectorArgument.contains("track in (stable,canary)"));
        assertTrue(selectorArgument.contains("zone notin (legacy)"));
        assertTrue(selectorArgument.contains("managed"));
        assertTrue(selectorArgument.contains("!retired"));
    }

    @Test
    void skipsEveryOwnershipAndPodQueryWhenRestartTrackingIsDisabled() throws Exception {
        AlertExecutionContext context = new AlertExecutionContext();
        FakeSession disabled = new FakeSession(ok(CONFIG), ok(deployment(1, 4, 1, 1, 1, 0)));
        AlertResult result = template(KubernetesWorkloadKind.Deployment, false, disabled).evaluate(context);
        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals(2, disabled.commands.size());
        assertEquals(Boolean.FALSE, result.statusMessage().get("restartTrackingEnabled"));
        assertFalse(result.statusMessage().containsKey("restartTotal"));
        assertFalse(result.statusMessage().containsKey("restartDelta"));
        assertFalse(result.statusMessage().containsKey("affectedContainerCount"));
        assertFalse(result.statusMessage().containsKey("affectedContainers"));

        FakeSession reenabled = deploymentSession(deployment(1, 4, 1, 1, 1, 0), pods(4, UID, "rs-uid"));
        AlertResult reenabledResult = template(KubernetesWorkloadKind.Deployment, true, reenabled).evaluate(context);
        assertEquals(AlertExecutionStatus.WARN, reenabledResult.status());
        assertEquals(4L, reenabledResult.statusMessage().get("restartDelta"));
    }

    @Test
    void discardsInvalidChangedIdentityAndOldStateAndPrunesDisappearedPods() throws Exception {
        AlertExecutionContext invalid = new AlertExecutionContext("not-json");
        AlertResult invalidResult = template(KubernetesWorkloadKind.Deployment, true,
                deploymentSession(deployment(1, 4, 1, 1, 1, 0), pods(3, UID, "rs-uid"))).evaluate(invalid);
        assertEquals(3L, invalidResult.statusMessage().get("restartDelta"));

        String oldState = invalid.getState();
        AlertExecutionContext oldVersion = new AlertExecutionContext(oldState.replace("\"version\":1", "\"version\":0"));
        AlertResult oldVersionResult = template(KubernetesWorkloadKind.Deployment, true,
                deploymentSession(deployment(1, 4, 1, 1, 1, 0), pods(2, UID, "rs-uid"))).evaluate(oldVersion);
        assertEquals(2L, oldVersionResult.statusMessage().get("restartDelta"));

        AlertExecutionContext changed = new AlertExecutionContext(oldState);
        String changedWorkload = deployment(1, 4, 1, 1, 1, 0).replace(UID, "replacement-workload");
        FakeSession changedSession = new FakeSession(ok(CONFIG), ok(changedWorkload), ok(replicaSets("replacement-workload", "replacement-rs")), ok(pods(2, UID, "replacement-rs")));
        AlertResult changedResult = template(KubernetesWorkloadKind.Deployment, true, changedSession).evaluate(changed);
        assertEquals(2L, changedResult.statusMessage().get("restartDelta"));
        assertFalse(changed.getState().contains("rs-uid"));
    }

    @Test
    void preservesPreviousStateOnOperationalFailureAndBaselineLimit() throws Exception {
        String previous = "previous-state";
        AlertExecutionContext failed = new AlertExecutionContext(previous);
        FakeSession failure = new FakeSession(ok(CONFIG), failed("kubernetesRbacDenied"));
        AlertResult failedResult = template(KubernetesWorkloadKind.Deployment, true, failure).evaluate(failed);
        assertEquals(AlertExecutionStatus.WARN, failedResult.status());
        assertEquals(previous, failed.getState());

        StringBuilder statuses = new StringBuilder("[");
        for (int index = 0; index <= KubernetesWorkloadAlertTemplate.MAX_BASELINE_ENTRIES; index++) {
            if (index > 0) statuses.append(',');
            statuses.append("{\"name\":\"c").append(index).append("\",\"restartCount\":1}");
        }
        statuses.append(']');
        String manyPods = "[{\"metadata\":{\"uid\":\"pod\",\"name\":\"pod\",\"ownerReferences\":[{\"uid\":\"rs-uid\",\"controller\":true}]},\"status\":{\"containerStatuses\":" + statuses + "}}]";
        AlertExecutionContext limited = new AlertExecutionContext(previous);
        FakeSession limit = deploymentSession(deployment(1, 4, 1, 1, 1, 0), podsList(manyPods));
        AlertResult limitResult = template(KubernetesWorkloadKind.Deployment, true, limit).evaluate(limited);
        assertEquals(AlertExecutionStatus.WARN, limitResult.status());
        assertTrue(reasons(limitResult).contains("restartBaselineLimitExceeded"));
        assertEquals(1001, limitResult.statusMessage().get("restartBaselineEntries"));
        assertEquals(previous, limited.getState());
    }

    @Test
    @SuppressWarnings("unchecked")
    void truncatesAffectedDetailsButReportsRealTotalsAndPrunesVanishedPods() throws Exception {
        StringBuilder statuses = new StringBuilder("[");
        for (int index = 0; index < 25; index++) {
            if (index > 0) statuses.append(',');
            statuses.append("{\"name\":\"c").append(index).append("\",\"restartCount\":1}");
        }
        statuses.append(']');
        String firstItems = "[{\"metadata\":{\"uid\":\"kept\",\"name\":\"kept\",\"ownerReferences\":[{\"uid\":\"rs-uid\",\"controller\":true}]},\"status\":{\"containerStatuses\":" + statuses + "}},{\"metadata\":{\"uid\":\"vanished\",\"name\":\"vanished\",\"ownerReferences\":[{\"uid\":\"rs-uid\",\"controller\":true}]},\"status\":{\"containerStatuses\":[{\"name\":\"main\",\"restartCount\":1}]}}]";
        AlertExecutionContext context = new AlertExecutionContext();
        AlertResult first = template(KubernetesWorkloadKind.Deployment, true,
                deploymentSession(deployment(1, 4, 1, 1, 1, 0), podsList(firstItems))).evaluate(context);
        assertEquals(26, first.statusMessage().get("affectedContainerCount"));
        assertEquals(26L, first.statusMessage().get("restartTotal"));
        assertEquals(20, ((List<Map<String, Object>>) first.statusMessage().get("affectedContainers")).size());

        template(KubernetesWorkloadKind.Deployment, true,
                deploymentSession(deployment(1, 4, 1, 1, 1, 0), pods(0, "kept", "rs-uid"))).evaluate(context);
        assertFalse(context.getState().contains("vanished"));
    }

    @Test
    void neverLeaksKubeconfigServerTokensCertificatesJsonOrStderr() throws Exception {
        String secret = "TOP-SECRET-MATERIAL";
        String config = CONFIG.replace("redacted", secret).replace("Y2E=", "VE9QLVNFQ1JFVC1DRVJU");
        AlertExecutionContext context = new AlertExecutionContext();
        AlertResult result = template(KubernetesWorkloadKind.Deployment, false,
                new FakeSession(ok(config), ok(deployment(1, 4, 1, 1, 1, 0)))).evaluate(context);
        String persisted = result.statusMessage().toString() + context.getState();
        assertFalse(persisted.contains(secret));
        assertFalse(persisted.contains("EXAMPLE.test"));
        assertFalse(persisted.contains("VE9QLVNFQ1JFVC1DRVJU"));
        assertFalse(persisted.contains("current-context"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> template(
                KubernetesWorkloadKind.Deployment, false,
                new FakeSession(new KubernetesWorkloadAlertTemplate.CommandResult(1, new byte[0], null, false, false))
        ).evaluate(new AlertExecutionContext()));
        assertEquals("kubeconfigInvalid", exception.getMessage());
    }

    @Test
    void rejectsUnsafeAndStructurallyInvalidKubeconfigsButTreatsOperationalValidationFailuresAsWarn() throws Exception {
        for (String unsafe : List.of("\"exec\":{}", "\"auth-provider\":{}", "\"tokenFile\":\"/tmp/token\"", "\"client-key\":\"/tmp/key\"", "\"client-certificate\":\"/tmp/cert\"")) {
            String config = CONFIG.replace("\"token\":\"redacted\"", unsafe);
            assertThrows(IllegalArgumentException.class, () -> template(KubernetesWorkloadKind.Deployment, false, new FakeSession(ok(config))).evaluate(new AlertExecutionContext()));
        }
        String pathCa = CONFIG.replace("\"server\":\"https://EXAMPLE.test:443\"", "\"server\":\"http://example.test\",\"certificate-authority\":\"/tmp/ca\"");
        assertThrows(IllegalArgumentException.class, () -> template(KubernetesWorkloadKind.Deployment, false, new FakeSession(ok(pathCa))).evaluate(new AlertExecutionContext()));

        String permitted = CONFIG.replace("\"server\":\"https://EXAMPLE.test:443\"", "\"server\":\"http://example.test\",\"insecure-skip-tls-verify\":true,\"proxy-url\":\"http://proxy.test\"");
        AlertResult accepted = template(KubernetesWorkloadKind.Deployment, false, new FakeSession(ok(permitted), ok(deployment(1, 4, 1, 1, 1, 0)))).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.SUCCESS, accepted.status());

        AlertResult timedOut = template(KubernetesWorkloadKind.Deployment, false, new FakeSession(timeout())).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.WARN, timedOut.status());
        assertEquals("kubectlTimeout", timedOut.statusMessage().get("reason"));
    }

    @Test
    void rejectsAWorkloadWhoseReturnedIdentityDoesNotExactlyMatchTheRequest() {
        String expected = deployment(1, 4, 1, 1, 1, 0);
        for (String unexpected : List.of(
                expected.replace("\"apiVersion\":\"apps/v1\"", "\"apiVersion\":\"v1\""),
                expected.replace("\"kind\":\"Deployment\"", "\"kind\":\"StatefulSet\""),
                expected.replace("\"name\":\"demo\"", "\"name\":\"other\""),
                expected.replace("\"namespace\":\"default\"", "\"namespace\":\"other\""))) {
            AlertExecutionContext context = new AlertExecutionContext("previous-state");
            FakeSession session = new FakeSession(ok(CONFIG), ok(unexpected));

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> template(KubernetesWorkloadKind.Deployment, true, session).evaluate(context));

            assertEquals("kubernetesWorkloadIdentityInvalid", exception.getMessage());
            assertEquals("previous-state", context.getState());
            assertEquals(2, session.commands.size());
        }
    }

    @Test
    void replacesTheOriginalKubeconfigWithOnlyTheSelectedCanonicalObjects() throws Exception {
        String selected = """
                {"apiVersion":"v1","kind":"Config","current-context":"ctx","preferences":{"colors":true},
                 "contexts":[{"name":"ctx","context":{"cluster":"cluster","user":"user","namespace":"ignored","extensions":[{"name":"context-extension"}]}}],
                 "clusters":[{"name":"cluster","cluster":{"server":"http://example.test","insecure-skip-tls-verify":true,"proxy-url":"http://proxy.test","certificate-authority-data":"Y2E=","extensions":[{"name":"cluster-extension"}]}}],
                 "users":[{"name":"user","user":{"token":"selected-token","client-certificate-data":"Y2VydA==","client-key-data":"a2V5","extensions":[{"name":"user-extension"}]}}],
                 "extensions":[{"name":"top-level-extension"}]}
                """;
        FakeSession session = new FakeSession(ok(selected), ok(deployment(1, 4, 1, 1, 1, 0)));

        AlertResult result = template(KubernetesWorkloadKind.Deployment, false, session).evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        JsonNode sanitized = JsonMapper.builder().build().readTree(session.sanitizedKubeconfig);
        assertEquals("v1", sanitized.path("apiVersion").stringValue());
        assertEquals("Config", sanitized.path("kind").stringValue());
        assertEquals("ctx", sanitized.path("current-context").stringValue());
        assertEquals(1, sanitized.path("contexts").size());
        assertEquals(1, sanitized.path("clusters").size());
        assertEquals(1, sanitized.path("users").size());
        assertEquals("selected-token", sanitized.path("users").get(0).path("user").path("token").stringValue());
        assertEquals("http://proxy.test", sanitized.path("clusters").get(0).path("cluster").path("proxy-url").stringValue());
        assertFalse(new String(session.sanitizedKubeconfig, StandardCharsets.UTF_8).contains("extension"));
        assertFalse(sanitized.path("contexts").get(0).path("context").has("namespace"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> reasons(AlertResult result) {
        return (List<String>) result.statusMessage().getOrDefault("reasons", List.of());
    }

    private static KubernetesWorkloadAlertTemplate template(KubernetesWorkloadKind kind, boolean restarts, FakeSession session) {
        return new KubernetesWorkloadAlertTemplate(new KubeconfigCredentials("apiVersion: v1\n"), "", "default", kind, "demo", restarts, 10, (_, _, _) -> session);
    }

    private static FakeSession deploymentSession(String workload, String podList) {
        return new FakeSession(ok(CONFIG), ok(workload), ok(replicaSets(UID, "rs-uid")), ok(podList));
    }

    private static FakeSession baseSession(String workload, String podList) {
        return new FakeSession(ok(CONFIG), ok(workload), ok(podList));
    }

    private static String deployment(long desired, long observed, long replicas, long updated, long ready, long unavailable) {
        long available = Math.max(0, desired - unavailable);
        return "{\"apiVersion\":\"apps/v1\",\"kind\":\"Deployment\",\"metadata\":{\"name\":\"demo\",\"namespace\":\"default\",\"uid\":\"" + UID + "\",\"generation\":4},\"spec\":{\"replicas\":" + desired + ",\"selector\":{\"matchLabels\":{\"app\":\"demo\"}}},\"status\":{\"observedGeneration\":" + observed + ",\"replicas\":" + replicas + ",\"updatedReplicas\":" + updated + ",\"readyReplicas\":" + ready + ",\"availableReplicas\":" + available + ",\"unavailableReplicas\":" + unavailable + "}}";
    }

    private static String statefulSet(long desired, long observed, long replicas, long ready, String current, String update) {
        return "{\"apiVersion\":\"apps/v1\",\"kind\":\"StatefulSet\",\"metadata\":{\"name\":\"demo\",\"namespace\":\"default\",\"uid\":\"" + UID + "\",\"generation\":4},\"spec\":{\"replicas\":" + desired + ",\"selector\":{\"matchLabels\":{\"app\":\"demo\"}}},\"status\":{\"observedGeneration\":" + observed + ",\"replicas\":" + replicas + ",\"readyReplicas\":" + ready + ",\"updatedReplicas\":" + ready + ",\"currentRevision\":\"" + current + "\",\"updateRevision\":\"" + update + "\"}}";
    }

    private static String daemonSet(long desired, long current, long updated, long ready, long available, long unavailable, long misscheduled) {
        return "{\"apiVersion\":\"apps/v1\",\"kind\":\"DaemonSet\",\"metadata\":{\"name\":\"demo\",\"namespace\":\"default\",\"uid\":\"" + UID + "\",\"generation\":4},\"spec\":{\"selector\":{\"matchLabels\":{\"app\":\"demo\"}}},\"status\":{\"observedGeneration\":4,\"desiredNumberScheduled\":" + desired + ",\"currentNumberScheduled\":" + current + ",\"updatedNumberScheduled\":" + updated + ",\"numberReady\":" + ready + ",\"numberAvailable\":" + available + ",\"numberUnavailable\":" + unavailable + ",\"numberMisscheduled\":" + misscheduled + "}}";
    }

    private static String replicaSets(String owner, String uid) {
        return "{\"items\":[{\"metadata\":{\"uid\":\"" + uid + "\",\"ownerReferences\":[{\"uid\":\"" + owner + "\",\"controller\":true}]}}]}";
    }

    private static String pods(long restarts, String podUid, String ownerUid) {
        return podsList("[{\"metadata\":{\"uid\":\"" + podUid + "\",\"name\":\"demo-pod\",\"ownerReferences\":[{\"uid\":\"" + ownerUid + "\",\"controller\":true}]},\"status\":{\"initContainerStatuses\":[{\"name\":\"init\",\"restartCount\":0}],\"containerStatuses\":[{\"name\":\"main\",\"restartCount\":" + restarts + "}],\"ephemeralContainerStatuses\":[{\"name\":\"debug\",\"restartCount\":99}]}}]");
    }

    private static String podsList(String items) {
        return "{\"items\":" + items + "}";
    }

    private static KubernetesWorkloadAlertTemplate.CommandResult ok(String json) {
        return new KubernetesWorkloadAlertTemplate.CommandResult(0, json.getBytes(StandardCharsets.UTF_8), null, false, false);
    }

    private static KubernetesWorkloadAlertTemplate.CommandResult failed(String category) {
        return new KubernetesWorkloadAlertTemplate.CommandResult(1, new byte[0], category, false, false);
    }

    private static KubernetesWorkloadAlertTemplate.CommandResult timeout() {
        return new KubernetesWorkloadAlertTemplate.CommandResult(-1, new byte[0], null, true, false);
    }

    private static final class FakeSession implements KubernetesWorkloadAlertTemplate.KubectlSession {
        private final ArrayDeque<KubernetesWorkloadAlertTemplate.CommandResult> results = new ArrayDeque<>();
        private final List<List<String>> commands = new ArrayList<>();
        private byte[] sanitizedKubeconfig;

        private FakeSession(KubernetesWorkloadAlertTemplate.CommandResult... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public KubernetesWorkloadAlertTemplate.CommandResult run(List<String> arguments) {
            if (!commands.isEmpty() && sanitizedKubeconfig == null)
                throw new AssertionError("A workload query ran before the kubeconfig was sanitized");

            commands.add(List.copyOf(arguments));
            return results.removeFirst();
        }

        @Override
        public void replaceKubeconfig(byte[] value) {
            sanitizedKubeconfig = value.clone();
        }

        @Override
        public void close() { }
    }
}
