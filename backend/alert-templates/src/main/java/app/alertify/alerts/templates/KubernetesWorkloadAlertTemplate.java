package app.alertify.alerts.templates;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;
import app.alertify.worker.contract.KubeconfigCredentials;
import app.alertify.worker.contract.WorkerCapability;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Read-only health and restart monitor for one Kubernetes workload. */
@AlertTemplate(
    nameKey = "alerts.template.kubernetesWorkload.name",
    descriptionKey = "alerts.template.kubernetesWorkload.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.kubernetes", color = "#326CE5"),
    capability = WorkerCapability.STANDARD,
    sourcePath = "app/alertify/alerts/templates/KubernetesWorkloadAlertTemplate.java"
)
public final class KubernetesWorkloadAlertTemplate implements AlertEvaluator {

    public enum KubernetesWorkloadKind {
        Deployment,
        StatefulSet,
        DaemonSet
    }

    static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024;
    static final int MAX_BASELINE_ENTRIES = 1000;
    private static final int STATE_VERSION = 1;
    private static final int MAX_AFFECTED = 20;
    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9](?:[-a-z0-9]*[a-z0-9])?$");
    private static final Pattern DNS_SUBDOMAIN = Pattern.compile("^[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?)*$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AlertParameter(
        labelKey = "alerts.template.kubernetesWorkload.credentials",
        descriptionKey = "alerts.template.kubernetesWorkload.credentialsDescription",
        allowedSources = AlertParameterSource.SECRET,
        allowedSecretValueTypes = "KUBECONFIG",
        order = 1
    )
    private final KubeconfigCredentials credentials;

    @AlertParameter(
        labelKey = "alerts.template.kubernetesWorkload.context",
        descriptionKey = "alerts.template.kubernetesWorkload.contextDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        required = false,
        order = 2
    )
    private final String context;

    @AlertParameter(
        labelKey = "alerts.template.kubernetesWorkload.namespace",
        descriptionKey = "alerts.template.kubernetesWorkload.namespaceDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        defaultValue = "default",
        order = 3
    )
    private final String namespace;

    @AlertParameter(
        labelKey = "alerts.template.kubernetesWorkload.kind",
        descriptionKey = "alerts.template.kubernetesWorkload.kindDescription",
        options = { "Deployment", "StatefulSet", "DaemonSet" },
        bindingAllowed = false,
        order = 4
    )
    private final KubernetesWorkloadKind kind;

    @AlertParameter(
        labelKey = "alerts.template.kubernetesWorkload.workloadName",
        descriptionKey = "alerts.template.kubernetesWorkload.workloadNameDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        order = 5
    )
    private final String name;

    @AlertParameter(
        labelKey = "alerts.template.kubernetesWorkload.warnOnRestarts",
        descriptionKey = "alerts.template.kubernetesWorkload.warnOnRestartsDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 6
    )
    private final boolean warnOnRestarts;

    @AlertParameter(
        labelKey = "alerts.template.kubernetesWorkload.timeoutSeconds",
        descriptionKey = "alerts.template.kubernetesWorkload.timeoutSecondsDescription",
        options = { "1", "3", "5", "10", "30" },
        bindingAllowed = false,
        defaultValue = "10",
        order = 7
    )
    private final int timeoutSeconds;

    private final KubectlFactory kubectlFactory;

    public KubernetesWorkloadAlertTemplate(KubeconfigCredentials credentials, String context, String namespace, KubernetesWorkloadKind kind, String name, boolean warnOnRestarts, int timeoutSeconds) {
        this(credentials, context, namespace, kind, name, warnOnRestarts, timeoutSeconds, ProcessKubectlSession::open);
    }

    KubernetesWorkloadAlertTemplate(KubeconfigCredentials credentials, String context, String namespace, KubernetesWorkloadKind kind, String name, boolean warnOnRestarts, int timeoutSeconds, KubectlFactory kubectlFactory) {
        this.credentials = credentials;
        this.context = context;
        this.namespace = namespace;
        this.kind = kind;
        this.name = name;
        this.warnOnRestarts = warnOnRestarts;
        this.timeoutSeconds = timeoutSeconds;
        this.kubectlFactory = kubectlFactory;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext executionContext) throws Exception {
        validateParameters();
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        try (KubectlSession kubectl = kubectlFactory.open(credentials, selectedContext(), deadlineNanos)) {
            ConfigIdentity cluster = validateKubeconfig(kubectl);
            CommandResult workloadResult = kubectl.run(workloadArguments());
            if (!workloadResult.success())
                return operationalWarning(workloadResult, "workloadQueryFailed");

            JsonNode workload = parseRequiredObject(workloadResult.stdout(), "workload");
            JsonNode workloadMetadata = validateWorkloadIdentity(workload);
            String workloadUid = requiredText(workloadMetadata, "uid");
            WorkloadIdentity identity = new WorkloadIdentity(cluster.fingerprint(), namespace, kind.name(), name, workloadUid);
            WorkloadHealth health = evaluateHealth(workload);

            if (!warnOnRestarts) {
                executionContext.setState(state(identity, Map.of(), false));
                return result(health, null, false, null);
            }

            String selector = selector(workload.path("spec").path("selector"));
            Set<String> podOwnerUids;
            try {
                podOwnerUids = switch (kind) {
                    case Deployment -> replicaSetUids(kubectl, selector, workloadUid);
                    case StatefulSet, DaemonSet -> Set.of(workloadUid);
                };
            } catch (OperationalException exception) {
                return operationalWarning(exception.code(), health);
            }
            CommandResult podResult = kubectl.run(listArguments("pods", selector));
            if (!podResult.success())
                return operationalWarning(podResult, "podQueryFailed", health);

            Map<String, RestartCounter> current = restartCounters(parseRequiredObject(podResult.stdout(), "pods"), podOwnerUids);
            if (current.size() > MAX_BASELINE_ENTRIES)
                return baselineLimitWarning(health, current);

            Baseline previous = baseline(executionContext.getState(), identity);
            RestartEvaluation restarts = evaluateRestarts(previous, current);
            executionContext.setState(state(identity, current, true));
            return result(health, restarts, true, null);
        } catch (OperationalException exception) {
            return AlertResult.warn(message("reason", exception.code(), "namespace", namespace, "kind", kind.name(), "name", name));
        }
    }

    private void validateParameters() {
        if (credentials == null)
            throw new IllegalArgumentException("kubeconfigMissing");
        if (kind == null)
            throw new IllegalArgumentException("workloadKindMissing");
        requireName(namespace, true, "namespaceInvalid");
        requireName(name, false, "workloadNameInvalid");
        if (timeoutSeconds != 1 && timeoutSeconds != 3 && timeoutSeconds != 5 && timeoutSeconds != 10 && timeoutSeconds != 30)
            throw new IllegalArgumentException("timeoutInvalid");

        String selected = selectedContext();
        if (selected != null && selected.indexOf('\0') >= 0)
            throw new IllegalArgumentException("contextInvalid");
    }

    private String selectedContext() {
        return context == null || context.isEmpty() ? null : context;
    }

    private static void requireName(String value, boolean label, String code) {
        int maximum = label ? 63 : 253;
        Pattern pattern = label ? DNS_LABEL : DNS_SUBDOMAIN;
        if (value == null || value.length() > maximum || !pattern.matcher(value).matches())
            throw new IllegalArgumentException(code);
    }

    private ConfigIdentity validateKubeconfig(KubectlSession kubectl) throws Exception {
        CommandResult result = kubectl.run(List.of("config", "view", "--raw", "--minify", "-o=json"));
        if (!result.success()) {
            if (result.timedOut() || result.outputLimitExceeded())
                throw new OperationalException(result.reason("kubeconfigQueryFailed"));
            throw new IllegalArgumentException("kubeconfigInvalid");
        }

        JsonNode config = parseRequiredObject(result.stdout(), "kubeconfig");
        ArrayNode contexts = requiredArray(config, "contexts");
        ArrayNode clusters = requiredArray(config, "clusters");
        ArrayNode users = optionalArray(config, "users");
        if (contexts.size() != 1 || clusters.size() != 1 || users.size() > 1)
            throw new IllegalArgumentException("kubeconfigReferencesInvalid");

        JsonNode selected = contexts.get(0);
        String contextName = requiredText(selected, "name");
        JsonNode contextValue = requiredObject(selected, "context");
        String clusterReference = requiredText(contextValue, "cluster");
        String userReference = optionalText(contextValue, "user");
        JsonNode clusterEntry = namedEntry(clusters, clusterReference, "kubeconfigClusterReferenceInvalid");
        JsonNode userEntry = userReference == null ? null : namedEntry(users, userReference, "kubeconfigUserReferenceInvalid");
        JsonNode cluster = requiredObject(clusterEntry, "cluster");
        rejectPath(cluster, "certificate-authority");
        if (userEntry != null) {
            JsonNode user = requiredObject(userEntry, "user");
            rejectField(user, "exec");
            rejectField(user, "auth-provider");
            rejectPath(user, "tokenFile");
            rejectPath(user, "client-key");
            rejectPath(user, "client-certificate");
        }

        String server = normalizedServer(requiredText(cluster, "server"));
        String ca = optionalText(cluster, "certificate-authority-data");
        byte[] caBytes;
        try {
            caBytes = ca == null ? new byte[0] : Base64.getMimeDecoder().decode(ca);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("kubeconfigCaInvalid");
        }
        String fingerprint = sha256(server + "\n" + contextName + "\n" + sha256(caBytes));
        kubectl.replaceKubeconfig(minimalKubeconfig(contextName, clusterReference, cluster, userReference, userEntry));
        return new ConfigIdentity(fingerprint, contextName);
    }

    private static byte[] minimalKubeconfig(String contextName, String clusterReference, JsonNode cluster, String userReference, JsonNode userEntry) {
        ObjectNode sanitized = JSON.createObjectNode();
        sanitized.put("apiVersion", "v1");
        sanitized.put("kind", "Config");
        sanitized.put("current-context", contextName);

        ObjectNode clusterValue = (ObjectNode) cluster.deepCopy();
        clusterValue.remove("certificate-authority");
        clusterValue.remove("extensions");
        ObjectNode sanitizedCluster = sanitized.putArray("clusters").addObject();
        sanitizedCluster.put("name", clusterReference);
        sanitizedCluster.set("cluster", clusterValue);

        ObjectNode sanitizedContext = sanitized.putArray("contexts").addObject();
        sanitizedContext.put("name", contextName);
        ObjectNode contextValue = sanitizedContext.putObject("context");
        contextValue.put("cluster", clusterReference);
        if (userReference != null)
            contextValue.put("user", userReference);

        ArrayNode users = sanitized.putArray("users");
        if (userEntry != null) {
            ObjectNode userValue = (ObjectNode) requiredObject(userEntry, "user").deepCopy();
            userValue.remove("exec");
            userValue.remove("auth-provider");
            userValue.remove("tokenFile");
            userValue.remove("client-key");
            userValue.remove("client-certificate");
            userValue.remove("extensions");
            ObjectNode sanitizedUser = users.addObject();
            sanitizedUser.put("name", userReference);
            sanitizedUser.set("user", userValue);
        }
        return JSON.writeValueAsBytes(sanitized);
    }

    private List<String> workloadArguments() {
        return List.of("get", resourceName(), name, "--namespace=" + namespace, "-o=json");
    }

    private JsonNode validateWorkloadIdentity(JsonNode workload) {
        JsonNode metadata = requiredObject(workload, "metadata");
        if (!"apps/v1".equals(requiredText(workload, "apiVersion"))
                || !kind.name().equals(requiredText(workload, "kind"))
                || !name.equals(requiredText(metadata, "name"))
                || !namespace.equals(requiredText(metadata, "namespace")))
            throw new IllegalArgumentException("kubernetesWorkloadIdentityInvalid");

        return metadata;
    }

    private String resourceName() {
        return switch (kind) {
            case Deployment -> "deployment";
            case StatefulSet -> "statefulset";
            case DaemonSet -> "daemonset";
        };
    }

    private List<String> listArguments(String resource, String selector) {
        List<String> arguments = new ArrayList<>(List.of("get", resource, "--namespace=" + namespace, "-o=json"));
        if (!selector.isEmpty())
            arguments.add("--selector=" + selector);
        return List.copyOf(arguments);
    }

    private Set<String> replicaSetUids(KubectlSession kubectl, String selector, String deploymentUid) throws Exception {
        CommandResult result = kubectl.run(listArguments("replicasets", selector));
        if (!result.success())
            throw new OperationalException(result.reason("replicaSetQueryFailed"));

        JsonNode list = parseRequiredObject(result.stdout(), "replicaSets");
        Set<String> uids = new LinkedHashSet<>();
        for (JsonNode replicaSet : requiredArray(list, "items")) {
            if (controlledBy(replicaSet, Set.of(deploymentUid)))
                uids.add(requiredText(replicaSet.path("metadata"), "uid"));
        }
        return Set.copyOf(uids);
    }

    private WorkloadHealth evaluateHealth(JsonNode workload) {
        JsonNode metadata = requiredObject(workload, "metadata");
        JsonNode spec = requiredObject(workload, "spec");
        JsonNode status = requiredObject(workload, "status");
        long generation = requiredLong(metadata, "generation");
        long observed = optionalLong(status, "observedGeneration", 0);
        LinkedHashMap<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("generation", generation);
        metrics.put("observedGeneration", observed);
        List<String> reasons = new ArrayList<>();
        if (observed < generation)
            reasons.add("generationNotObserved");

        switch (kind) {
            case Deployment -> deploymentHealth(spec, status, metrics, reasons);
            case StatefulSet -> statefulSetHealth(spec, status, metrics, reasons);
            case DaemonSet -> daemonSetHealth(status, metrics, reasons);
        }
        return new WorkloadHealth(Map.copyOf(metrics), List.copyOf(reasons));
    }

    private static void deploymentHealth(JsonNode spec, JsonNode status, Map<String, Long> metrics, List<String> reasons) {
        long desired = optionalLong(spec, "replicas", 1);
        compareMetric(status, "replicas", desired, metrics, reasons, "replicasMismatch");
        compareMetric(status, "updatedReplicas", desired, metrics, reasons, "updatedReplicasMismatch");
        compareMetric(status, "readyReplicas", desired, metrics, reasons, "readyReplicasMismatch");
        compareMetric(status, "availableReplicas", desired, metrics, reasons, "availableReplicasMismatch");
        compareMetric(status, "unavailableReplicas", 0, metrics, reasons, "unavailableReplicasPresent");
        metrics.put("desiredReplicas", desired);
    }

    private static void statefulSetHealth(JsonNode spec, JsonNode status, Map<String, Long> metrics, List<String> reasons) {
        long desired = optionalLong(spec, "replicas", 1);
        compareMetric(status, "replicas", desired, metrics, reasons, "replicasMismatch");
        compareMetric(status, "readyReplicas", desired, metrics, reasons, "readyReplicasMismatch");
        compareMetric(status, "updatedReplicas", desired, metrics, reasons, "updatedReplicasMismatch");
        metrics.put("desiredReplicas", desired);
        String current = optionalText(status, "currentRevision");
        String target = optionalText(status, "updateRevision");
        if (!Objects.equals(current, target) || (desired > 0 && (current == null || target == null)))
            reasons.add("revisionMismatch");
    }

    private static void daemonSetHealth(JsonNode status, Map<String, Long> metrics, List<String> reasons) {
        long desired = requiredLong(status, "desiredNumberScheduled");
        compareMetric(status, "currentNumberScheduled", desired, metrics, reasons, "scheduledNodesMismatch");
        compareMetric(status, "updatedNumberScheduled", desired, metrics, reasons, "updatedNodesMismatch");
        compareMetric(status, "numberReady", desired, metrics, reasons, "readyNodesMismatch");
        compareMetric(status, "numberAvailable", desired, metrics, reasons, "availableNodesMismatch");
        compareMetric(status, "numberUnavailable", 0, metrics, reasons, "unavailableNodesPresent");
        compareMetric(status, "numberMisscheduled", 0, metrics, reasons, "misscheduledNodesPresent");
        metrics.put("desiredNodes", desired);
    }

    private static void compareMetric(JsonNode status, String field, long expected, Map<String, Long> metrics, List<String> reasons, String reason) {
        long actual = optionalLong(status, field, 0);
        metrics.put(field, actual);
        if (actual != expected)
            reasons.add(reason);
    }

    private static String selector(JsonNode selector) {
        if (!selector.isObject())
            throw new IllegalArgumentException("workloadSelectorInvalid");

        List<String> components = new ArrayList<>();
        JsonNode labels = selector.get("matchLabels");
        if (labels != null && !labels.isNull()) {
            if (!labels.isObject())
                throw new IllegalArgumentException("workloadSelectorInvalid");
            List<String> keys = new ArrayList<>();
            labels.propertyNames().forEach(keys::add);
            keys.sort(String::compareTo);
            for (String key : keys) {
                JsonNode value = labels.get(key);
                if (!value.isString())
                    throw new IllegalArgumentException("workloadSelectorInvalid");
                components.add(key + "=" + value.stringValue());
            }
        }
        JsonNode expressions = selector.get("matchExpressions");
        if (expressions != null && !expressions.isNull()) {
            if (!expressions.isArray())
                throw new IllegalArgumentException("workloadSelectorInvalid");
            for (JsonNode expression : expressions) {
                String key = requiredText(expression, "key");
                String operator = requiredText(expression, "operator");
                List<String> values = new ArrayList<>();
                JsonNode valueNodes = expression.get("values");
                if (valueNodes != null) {
                    if (!valueNodes.isArray())
                        throw new IllegalArgumentException("workloadSelectorInvalid");
                    for (JsonNode value : valueNodes) {
                        if (!value.isString())
                            throw new IllegalArgumentException("workloadSelectorInvalid");
                        values.add(value.stringValue());
                    }
                }
                components.add(switch (operator) {
                    case "In" -> key + " in (" + String.join(",", values) + ")";
                    case "NotIn" -> key + " notin (" + String.join(",", values) + ")";
                    case "Exists" -> key;
                    case "DoesNotExist" -> "!" + key;
                    default -> throw new IllegalArgumentException("workloadSelectorOperatorInvalid");
                });
            }
        }
        return String.join(",", components);
    }

    private static Map<String, RestartCounter> restartCounters(JsonNode podList, Set<String> allowedOwnerUids) {
        Map<String, RestartCounter> counters = new LinkedHashMap<>();
        for (JsonNode pod : requiredArray(podList, "items")) {
            if (!controlledBy(pod, allowedOwnerUids))
                continue;

            JsonNode metadata = requiredObject(pod, "metadata");
            String uid = requiredText(metadata, "uid");
            String podName = requiredText(metadata, "name");
            JsonNode status = requiredObject(pod, "status");
            addCounters(counters, status.get("initContainerStatuses"), uid, podName, "init");
            addCounters(counters, status.get("containerStatuses"), uid, podName, "container");
        }
        return Map.copyOf(counters);
    }

    private static boolean controlledBy(JsonNode resource, Set<String> allowedUids) {
        JsonNode references = resource.path("metadata").get("ownerReferences");
        if (references == null || !references.isArray())
            return false;
        for (JsonNode reference : references) {
            if (reference.path("controller").asBoolean(false) && allowedUids.contains(optionalText(reference, "uid")))
                return true;
        }
        return false;
    }

    private static void addCounters(Map<String, RestartCounter> counters, JsonNode statuses, String podUid, String podName, String type) {
        if (statuses == null || statuses.isNull())
            return;
        if (!statuses.isArray())
            throw new IllegalArgumentException("podStatusesInvalid");
        for (JsonNode status : statuses) {
            String container = requiredText(status, "name");
            long count = requiredLong(status, "restartCount");
            if (count < 0)
                throw new IllegalArgumentException("podRestartCountInvalid");
            String key = podUid + "/" + type + "/" + container;
            counters.put(key, new RestartCounter(podUid, podName, type, container, count));
        }
    }

    private static RestartEvaluation evaluateRestarts(Baseline previous, Map<String, RestartCounter> current) {
        List<RestartDelta> affected = new ArrayList<>();
        long total = 0;
        long totalDelta = 0;
        boolean first = previous == null;
        for (Map.Entry<String, RestartCounter> entry : current.entrySet()) {
            RestartCounter counter = entry.getValue();
            total += counter.count();
            RestartCounter old = first ? null : previous.counters().get(entry.getKey());
            long delta = first ? counter.count() : old == null || counter.count() <= old.count() ? 0 : counter.count() - old.count();
            if (delta > 0) {
                totalDelta += delta;
                affected.add(new RestartDelta(counter.podName(), counter.type(), counter.container(), counter.count(), delta));
            }
        }
        affected.sort(Comparator.comparing(RestartDelta::pod).thenComparing(RestartDelta::type).thenComparing(RestartDelta::container));
        return new RestartEvaluation(total, totalDelta, List.copyOf(affected));
    }

    private static Baseline baseline(String state, WorkloadIdentity identity) {
        if (state == null || state.isBlank())
            return null;
        try {
            JsonNode root = JSON.readTree(state);
            if (!root.isObject() || root.path("version").asInt(-1) != STATE_VERSION
                    || !root.path("restartTrackingEnabled").asBoolean(false)
                    || !identity.clusterFingerprint().equals(optionalText(root, "clusterFingerprint"))
                    || !identity.namespace().equals(optionalText(root, "namespace"))
                    || !identity.kind().equals(optionalText(root, "kind"))
                    || !identity.name().equals(optionalText(root, "name"))
                    || !identity.workloadUid().equals(optionalText(root, "workloadUid")))
                return null;
            JsonNode entries = root.get("counters");
            if (entries == null || !entries.isArray() || entries.size() > MAX_BASELINE_ENTRIES)
                return null;
            Map<String, RestartCounter> counters = new LinkedHashMap<>();
            for (JsonNode entry : entries) {
                String key = requiredText(entry, "key");
                RestartCounter counter = new RestartCounter(
                        requiredText(entry, "podUid"), requiredText(entry, "pod"), requiredText(entry, "type"),
                        requiredText(entry, "container"), requiredLong(entry, "count")
                );
                if (counter.count() < 0 || counters.put(key, counter) != null)
                    return null;
            }
            return new Baseline(Map.copyOf(counters));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String state(WorkloadIdentity identity, Map<String, RestartCounter> counters, boolean restartTrackingEnabled) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", STATE_VERSION);
        root.put("clusterFingerprint", identity.clusterFingerprint());
        root.put("namespace", identity.namespace());
        root.put("kind", identity.kind());
        root.put("name", identity.name());
        root.put("workloadUid", identity.workloadUid());
        root.put("restartTrackingEnabled", restartTrackingEnabled);
        ArrayNode entries = root.putArray("counters");
        counters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            RestartCounter counter = entry.getValue();
            ObjectNode node = entries.addObject();
            node.put("key", entry.getKey());
            node.put("podUid", counter.podUid());
            node.put("pod", counter.podName());
            node.put("type", counter.type());
            node.put("container", counter.container());
            node.put("count", counter.count());
        });
        return JSON.writeValueAsString(root);
    }

    private AlertResult result(WorkloadHealth health, RestartEvaluation restarts, boolean restartTrackingEnabled, String extraReason) {
        LinkedHashMap<String, Object> status = new LinkedHashMap<>();
        status.put("namespace", namespace);
        status.put("kind", kind.name());
        status.put("name", name);
        status.put("metrics", health.metrics());
        status.put("restartTrackingEnabled", restartTrackingEnabled);
        List<String> reasons = new ArrayList<>(health.reasons());
        if (restartTrackingEnabled && restarts.totalDelta() > 0)
            reasons.add("restartCountIncreased");
        if (extraReason != null)
            reasons.add(extraReason);
        if (!reasons.isEmpty())
            status.put("reasons", List.copyOf(reasons));
        if (restartTrackingEnabled) {
            status.put("restartTotal", restarts.total());
            status.put("restartDelta", restarts.totalDelta());
            status.put("affectedContainerCount", restarts.affected().size());
            if (!restarts.affected().isEmpty()) {
                status.put("affectedContainers", restarts.affected().stream().limit(MAX_AFFECTED).map(delta -> Map.of(
                        "pod", delta.pod(), "type", delta.type(), "container", delta.container(),
                        "restartCount", delta.count(), "delta", delta.delta()
                )).toList());
            }
        }
        return reasons.isEmpty() ? AlertResult.success(status) : AlertResult.warn(status);
    }

    private AlertResult operationalWarning(CommandResult result, String fallback) {
        return AlertResult.warn(message("reason", result.reason(fallback), "namespace", namespace, "kind", kind.name(), "name", name));
    }

    private AlertResult operationalWarning(CommandResult result, String fallback, WorkloadHealth health) {
        return operationalWarning(result.reason(fallback), health);
    }

    private AlertResult operationalWarning(String reason, WorkloadHealth health) {
        LinkedHashMap<String, Object> status = new LinkedHashMap<>();
        status.put("reason", reason);
        status.put("namespace", namespace);
        status.put("kind", kind.name());
        status.put("name", name);
        status.put("metrics", health.metrics());
        List<String> reasons = new ArrayList<>(health.reasons());
        reasons.add(reason);
        status.put("reasons", List.copyOf(reasons));
        return AlertResult.warn(status);
    }

    private AlertResult baselineLimitWarning(WorkloadHealth health, Map<String, RestartCounter> counters) {
        LinkedHashMap<String, Object> status = new LinkedHashMap<>();
        status.put("namespace", namespace);
        status.put("kind", kind.name());
        status.put("name", name);
        status.put("metrics", health.metrics());
        List<String> reasons = new ArrayList<>(health.reasons());
        reasons.add("restartBaselineLimitExceeded");
        status.put("reasons", List.copyOf(reasons));
        status.put("restartBaselineEntries", counters.size());
        status.put("restartTotal", counters.values().stream().mapToLong(RestartCounter::count).sum());
        return AlertResult.warn(status);
    }

    private static LinkedHashMap<String, Object> message(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2)
            result.put((String) values[index], values[index + 1]);
        return result;
    }

    private static JsonNode parseRequiredObject(byte[] bytes, String label) {
        try {
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject())
                throw new IllegalArgumentException(label + "JsonInvalid");
            return node;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + "JsonInvalid");
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject())
            throw new IllegalArgumentException("kubernetesJsonStructureInvalid");
        return value;
    }

    private static ArrayNode requiredArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (!(value instanceof ArrayNode array))
            throw new IllegalArgumentException("kubernetesJsonStructureInvalid");
        return array;
    }

    private static ArrayNode optionalArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull())
            return JSON.createArrayNode();
        if (!(value instanceof ArrayNode array))
            throw new IllegalArgumentException("kubernetesJsonStructureInvalid");
        return array;
    }

    private static String requiredText(JsonNode parent, String field) {
        String value = optionalText(parent, field);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("kubernetesJsonStructureInvalid");
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return value == null || value.isNull() ? null : value.isString() ? value.stringValue() : null;
    }

    private static long requiredLong(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
            throw new IllegalArgumentException("kubernetesJsonStructureInvalid");
        return value.longValue();
    }

    private static long optionalLong(JsonNode parent, String field, long defaultValue) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull())
            return defaultValue;
        if (!value.isIntegralNumber() || !value.canConvertToLong())
            throw new IllegalArgumentException("kubernetesJsonStructureInvalid");
        return value.longValue();
    }

    private static JsonNode namedEntry(ArrayNode values, String name, String code) {
        for (JsonNode value : values) {
            if (name.equals(optionalText(value, "name")))
                return value;
        }
        throw new IllegalArgumentException(code);
    }

    private static void rejectField(JsonNode node, String field) {
        if (node.has(field))
            throw new IllegalArgumentException("kubeconfigUnsafeCredentialProvider");
    }

    private static void rejectPath(JsonNode node, String field) {
        if (node.has(field))
            throw new IllegalArgumentException("kubeconfigLocalPathForbidden");
    }

    private static String normalizedServer(String server) {
        try {
            URI uri = new URI(server).normalize();
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            if (scheme == null || host == null)
                throw new IllegalArgumentException("kubeconfigServerInvalid");
            int port = uri.getPort();
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80))
                port = -1;
            return new URI(scheme, uri.getUserInfo(), host, port, uri.getPath(), uri.getQuery(), null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("kubeconfigServerInvalid");
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256Unavailable");
        }
    }

    @FunctionalInterface
    interface KubectlFactory {
        KubectlSession open(KubeconfigCredentials credentials, String context, long deadlineNanos) throws Exception;
    }

    interface KubectlSession extends AutoCloseable {
        CommandResult run(List<String> arguments) throws Exception;
        void replaceKubeconfig(byte[] value) throws Exception;
        @Override void close() throws Exception;
    }

    record CommandResult(int exitCode, byte[] stdout, String stderrCategory, boolean timedOut, boolean outputLimitExceeded) {
        boolean success() {
            return exitCode == 0 && !timedOut && !outputLimitExceeded;
        }

        String reason(String fallback) {
            if (outputLimitExceeded)
                return "kubectlOutputLimitExceeded";
            if (timedOut)
                return "kubectlTimeout";
            return stderrCategory == null ? fallback : stderrCategory;
        }
    }

    static final class ProcessKubectlSession implements KubectlSession {
        private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
        private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
        private static final byte[] KUBERC = ("apiVersion: kubectl.config.k8s.io/v1beta1\n"
                + "kind: Preference\ncredentialPluginPolicy: DenyAll\n").getBytes(StandardCharsets.UTF_8);

        private final Path directory;
        private final Path kubeconfig;
        private final Path kuberc;
        private final Path home;
        private final Path executable;
        private final String context;
        private final long deadlineNanos;
        private volatile Process activeProcess;

        private ProcessKubectlSession(Path directory, Path kubeconfig, Path kuberc, Path home, Path executable, String context, long deadlineNanos) {
            this.directory = directory;
            this.kubeconfig = kubeconfig;
            this.kuberc = kuberc;
            this.home = home;
            this.executable = executable;
            this.context = context;
            this.deadlineNanos = deadlineNanos;
        }

        static ProcessKubectlSession open(KubeconfigCredentials credentials, String context, long deadlineNanos) throws IOException {
            return open(credentials, context, deadlineNanos, Path.of("/usr/local/bin/kubectl"));
        }

        static ProcessKubectlSession open(KubeconfigCredentials credentials, String context, long deadlineNanos, Path executable) throws IOException {
            Path directory = Files.createTempDirectory("alertify-kubernetes-", PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            try {
                Path home = Files.createDirectory(directory.resolve("home"), PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
                Path kubeconfig = createFile(directory.resolve("kubeconfig"), credentials.kubeconfig().getBytes(StandardCharsets.UTF_8));
                Path kuberc = createFile(directory.resolve("kuberc"), KUBERC);
                return new ProcessKubectlSession(directory, kubeconfig, kuberc, home, executable, context, deadlineNanos);
            } catch (IOException | RuntimeException exception) {
                deleteTree(directory);
                throw new IllegalStateException("kubeconfigTemporaryFilesFailed");
            }
        }

        private static Path createFile(Path path, byte[] value) throws IOException {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            Files.write(path, value, StandardOpenOption.WRITE);
            return path;
        }

        Path directory() { return directory; }
        Path kubeconfig() { return kubeconfig; }
        Path kuberc() { return kuberc; }
        Path home() { return home; }

        @Override
        public void replaceKubeconfig(byte[] value) {
            Path replacement = null;
            try {
                replacement = Files.createTempFile(directory, "kubeconfig-sanitized-", ".tmp", PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
                Files.write(replacement, value, StandardOpenOption.WRITE);
                Files.move(replacement, kubeconfig, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new IllegalStateException("kubeconfigSanitizationFailed");
            } finally {
                if (replacement != null) {
                    try {
                        Files.deleteIfExists(replacement);
                    } catch (IOException ignored) {
                        // The session cleanup retries deletion of every remaining temporary file.
                    }
                }
            }
        }

        @Override
        public CommandResult run(List<String> arguments) throws Exception {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0)
                throw new OperationalException("kubectlTimeout");

            long requestTimeoutMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.add("--kubeconfig=" + kubeconfig);
            command.add("--kuberc=" + kuberc);
            if (context != null)
                command.add("--context=" + context);
            command.add("--request-timeout=" + requestTimeoutMillis + "ms");
            command.addAll(arguments);

            Process process;
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.environment().clear();
                builder.environment().put("HOME", home.toString());
                builder.environment().put("KUBECONFIG", kubeconfig.toString());
                builder.environment().put("KUBERC", kuberc.toString());
                process = builder.start();
            } catch (IOException exception) {
                throw new IllegalStateException("kubectlUnavailable");
            }
            activeProcess = process;
            AtomicLong totalBytes = new AtomicLong();
            AtomicBoolean exceeded = new AtomicBoolean();
            try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<byte[]> stdout = readers.submit(() -> read(process.getInputStream(), totalBytes, exceeded, process));
                Future<byte[]> stderr = readers.submit(() -> read(process.getErrorStream(), totalBytes, exceeded, process));
                boolean finished;
                try {
                    long waitNanos = deadlineNanos - System.nanoTime();
                    finished = waitNanos > 0 && process.waitFor(waitNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    terminate(process);
                    Thread.currentThread().interrupt();
                    throw exception;
                }
                if (!finished) {
                    terminate(process);
                    return new CommandResult(-1, new byte[0], null, true, false);
                }
                if (exceeded.get()) {
                    terminate(process);
                    return new CommandResult(-1, new byte[0], null, false, true);
                }
                byte[] out = future(stdout, deadlineNanos);
                byte[] error = future(stderr, deadlineNanos);
                if (exceeded.get())
                    return new CommandResult(-1, new byte[0], null, false, true);
                return new CommandResult(process.exitValue(), out, classify(error), false, false);
            } finally {
                terminate(process);
                activeProcess = null;
            }
        }

        private static byte[] read(InputStream input, AtomicLong totalBytes, AtomicBoolean exceeded, Process process) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            try {
                while ((count = input.read(buffer)) >= 0) {
                    if (totalBytes.addAndGet(count) > MAX_OUTPUT_BYTES) {
                        exceeded.set(true);
                        terminate(process);
                        break;
                    }
                    output.write(buffer, 0, count);
                }
            } catch (IOException exception) {
                if (!exceeded.get())
                    throw exception;
            }
            return output.toByteArray();
        }

        private static byte[] future(Future<byte[]> future, long deadlineNanos) throws IOException, InterruptedException, OperationalException {
            try {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0)
                    throw new OperationalException("kubectlTimeout");
                return future.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof IOException ioException)
                    throw ioException;
                throw new IOException("kubectlOutputReadFailed", cause);
            } catch (TimeoutException exception) {
                throw new OperationalException("kubectlTimeout");
            }
        }

        private static String classify(byte[] stderr) {
            String value = new String(stderr, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (value.contains("notfound") || value.contains("not found"))
                return "workloadNotFound";
            if (value.contains("forbidden") || value.contains("permission") || value.contains("cannot list") || value.contains("cannot get"))
                return "kubernetesRbacDenied";
            if (value.contains("unauthorized") || value.contains("authentication") || value.contains("credentials"))
                return "kubernetesAuthenticationFailed";
            if (value.contains("timeout") || value.contains("deadline exceeded"))
                return "kubectlTimeout";
            if (value.contains("connection refused") || value.contains("unable to connect") || value.contains("no such host"))
                return "kubernetesConnectionFailed";
            return null;
        }

        private static void terminate(Process process) {
            if (process == null || !process.isAlive())
                return;
            List<ProcessHandle> descendants = process.descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            process.destroy();
            try {
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            terminate(activeProcess);
            for (int attempt = 0; attempt < 3 && Files.exists(directory, LinkOption.NOFOLLOW_LINKS); attempt++)
                deleteTree(directory);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("kubeconfigTemporaryCleanupFailed");
        }

        private static void deleteTree(Path path) {
            if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                return;
            try {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                        if (exception != null)
                            throw exception;

                        Files.deleteIfExists(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ignored) { }
        }
    }

    private static final class OperationalException extends Exception {
        private final String code;

        private OperationalException(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private record ConfigIdentity(String fingerprint, String context) { }
    private record WorkloadIdentity(String clusterFingerprint, String namespace, String kind, String name, String workloadUid) { }
    private record WorkloadHealth(Map<String, Long> metrics, List<String> reasons) { }
    private record RestartCounter(String podUid, String podName, String type, String container, long count) { }
    private record RestartDelta(String pod, String type, String container, long count, long delta) { }
    private record RestartEvaluation(long total, long totalDelta, List<RestartDelta> affected) { }
    private record Baseline(Map<String, RestartCounter> counters) { }
}
