package app.alertify.alerts.templates;

import java.io.ByteArrayInputStream;
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
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;

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

/** Reads the RKE2 kube-proxy client certificate from each node's existing pod. */
@AlertTemplate(
    nameKey = "alerts.template.kubernetesKubeProxyCertificate.name",
    descriptionKey = "alerts.template.kubernetesKubeProxyCertificate.description",
    tags = {
        @AlertTemplateTag(nameKey = "alerts.templateTag.kubernetes", color = "#326CE5"),
        @AlertTemplateTag(nameKey = "alerts.templateTag.security", color = "#7C3AED")
    },
    capability = WorkerCapability.STANDARD,
    sourcePath = "app/alertify/alerts/templates/KubernetesKubeProxyCertificateExpiryAlertTemplate.java"
)
public final class KubernetesKubeProxyCertificateExpiryAlertTemplate implements AlertEvaluator {
    static final String CERTIFICATE_PATH = "/var/lib/rancher/rke2/agent/client-kube-proxy.crt";
    static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024;
    private static final String NAMESPACE = "kube-system";
    private static final String CLUSTER_FIELD = "cluster";
    private static final String JSON_OUTPUT_ARGUMENT = "-o=json";
    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9](?:[-a-z0-9]*[a-z0-9])?$");
    private static final Pattern DNS_SUBDOMAIN = Pattern.compile("^[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?)*$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AlertParameter(
        labelKey = "alerts.template.kubernetesWorkload.credentials",
        descriptionKey = "alerts.template.kubernetesKubeProxyCertificate.credentialsDescription",
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
        labelKey = "alerts.template.kubernetesKubeProxyCertificate.podSelector",
        descriptionKey = "alerts.template.kubernetesKubeProxyCertificate.podSelectorDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        bindingAllowed = true,
        defaultValue = "component=kube-proxy",
        order = 3
    )
    private final String podSelector;

    @AlertParameter(
        labelKey = "alerts.template.kubernetesKubeProxyCertificate.container",
        descriptionKey = "alerts.template.kubernetesKubeProxyCertificate.containerDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        bindingAllowed = true,
        defaultValue = "kube-proxy",
        order = 4
    )
    private final String container;

    @AlertParameter(
        labelKey = "alerts.template.httpsCertificate.warningDays",
        descriptionKey = "alerts.template.httpsCertificate.warningDaysDescription",
        options = { "7", "14", "30", "60", "90" },
        bindingAllowed = true,
        defaultValue = "30",
        order = 5
    )
    private final int warningDays;

    @AlertParameter(
        labelKey = "alerts.template.kubernetesKubeProxyCertificate.timeout",
        descriptionKey = "alerts.template.kubernetesKubeProxyCertificate.timeoutDescription",
        options = { "3", "5", "10", "30" },
        bindingAllowed = false,
        defaultValue = "10",
        order = 6
    )
    private final int timeoutSeconds;

    private final KubectlFactory kubectlFactory;
    private final Clock clock;

    public KubernetesKubeProxyCertificateExpiryAlertTemplate(KubeconfigCredentials credentials, String context, String podSelector, String container, int warningDays, int timeoutSeconds) {
        this(credentials, context, podSelector, container, warningDays, timeoutSeconds, ProcessKubectlSession::open, Clock.systemUTC());
    }

    KubernetesKubeProxyCertificateExpiryAlertTemplate(KubeconfigCredentials credentials, String context, String podSelector, String container, int warningDays, int timeoutSeconds, KubectlFactory kubectlFactory, Clock clock) {
        this.credentials = credentials;
        this.context = context;
        this.podSelector = podSelector;
        this.container = container;
        this.warningDays = warningDays;
        this.timeoutSeconds = timeoutSeconds;
        this.kubectlFactory = kubectlFactory;
        this.clock = clock;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext executionContext) throws Exception {
        validateParameters();
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> discoveredNodes = new ArrayList<>();
        try (KubectlSession kubectl = kubectlFactory.open(credentials, selectedContext(), timeoutSeconds)) {
            validateKubeconfig(kubectl);
            discoveredNodes.addAll(nodes(kubectl));
            if (discoveredNodes.isEmpty())
                return result(results, "kubeProxyNoNodes");

            ArrayNode pods = requiredArray(query(kubectl, List.of("get", "pods", "--namespace=" + NAMESPACE, "--selector=" + podSelector, JSON_OUTPUT_ARGUMENT), "kubeProxyPodQueryFailed"), "items");
            Instant now = clock.instant();
            for (String node : discoveredNodes) {
                try {
                    results.add(checkNode(kubectl, node, pods, now));
                } catch (OperationalException exception) {
                    results.add(nodeWarning(node, exception.code()));
                } catch (IllegalArgumentException exception) {
                    results.add(nodeWarning(node, "kubernetesJsonStructureInvalid"));
                } catch (IOException | IllegalStateException exception) {
                    results.add(nodeWarning(node, "kubeProxyCertificateReadFailed"));
                }
            }
            return result(results, null);
        } catch (OperationalException exception) {
            return failedInspection(discoveredNodes, results, exception.code());
        } catch (IllegalArgumentException exception) {
            return failedInspection(discoveredNodes, results, "kubeProxyInvalidResponse");
        } catch (IOException | IllegalStateException exception) {
            return failedInspection(discoveredNodes, results, "kubeProxyInspectionFailed");
        }
    }

    private void validateParameters() {
        if (credentials == null)
            throw new IllegalArgumentException("kubeconfigMissing");

        if (podSelector == null || podSelector.isBlank() || podSelector.indexOf('\0') >= 0)
            throw new IllegalArgumentException("podSelectorInvalid");

        requireName(container, true, "containerNameInvalid");
        if (warningDays < 0)
            throw new IllegalArgumentException("warningDays must not be negative");

        if (timeoutSeconds != 3 && timeoutSeconds != 5 && timeoutSeconds != 10 && timeoutSeconds != 30)
            throw new IllegalArgumentException("timeoutInvalid");

        if (context != null && context.indexOf('\0') >= 0)
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

    private static JsonNode query(KubectlSession kubectl, List<String> arguments, String failure) throws Exception {
        CommandResult result = kubectl.run(arguments);
        if (!result.success())
            throw new OperationalException(result.reason(failure));

        return parseRequiredObject(result.stdout(), "response");
    }

    private static List<String> nodes(KubectlSession kubectl) throws Exception {
        ArrayNode items = requiredArray(query(kubectl, List.of("get", "nodes", JSON_OUTPUT_ARGUMENT), "kubeProxyNodeQueryFailed"), "items");
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode item : items) {
            String name = requiredText(requiredObject(item, "metadata"), "name");
            requireName(name, false, "nodeNameInvalid");
            if (!names.add(name))
                throw new OperationalException("kubeProxyInvalidResponse");
        }
        return names.stream().sorted().toList();
    }

    private Map<String, Object> checkNode(KubectlSession kubectl, String node, ArrayNode pods, Instant now) throws Exception {
        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode pod : pods) {
            JsonNode metadata = requiredObject(pod, "metadata");
            String phase = optionalText(pod.path("status"), "phase");
            if (node.equals(optionalText(pod.path("spec"), "nodeName"))
                    && NAMESPACE.equals(optionalText(metadata, "namespace"))
                    && !metadata.hasNonNull("deletionTimestamp")
                    && !"Succeeded".equals(phase) && !"Failed".equals(phase))
                candidates.add(pod);
        }
        if (candidates.size() != 1)
            return nodeWarning(node, candidates.isEmpty() ? "kubeProxyPodMissing" : "kubeProxyPodAmbiguous");

        JsonNode pod = candidates.getFirst();
        String podName = requiredText(pod.path("metadata"), "name");
        requireName(podName, false, "podNameInvalid");
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("node", node);
        status.put("pod", podName);
        if (!containerRunning(pod)) {
            status.put("status", "WARN");
            status.put("reason", "kubeProxyContainerNotRunning");
            return status;
        }

        CommandResult read = kubectl.run(List.of("exec", "--namespace=" + NAMESPACE, podName, "--container=" + container, "--", "cat", CERTIFICATE_PATH));
        if (!read.success()) {
            status.put("status", "WARN");
            status.put("reason", read.reason("kubeProxyCertificateReadFailed"));
            return status;
        }
        try {
            X509Certificate certificate = certificate(read.stdout());
            Instant notBefore = certificate.getNotBefore().toInstant();
            Instant notAfter = certificate.getNotAfter().toInstant();
            long daysRemaining = ChronoUnit.DAYS.between(now, notAfter);
            String reason = now.isAfter(notAfter) ? "kubeProxyCertificateExpired"
                    : now.isBefore(notBefore) ? "kubeProxyCertificateNotYetValid"
                    : daysRemaining <= warningDays ? "kubeProxyCertificateExpiring" : null;
            status.put("subject", certificate.getSubjectX500Principal().getName());
            status.put("notBefore", notBefore.toString());
            status.put("notAfter", notAfter.toString());
            status.put("daysRemaining", daysRemaining);
            status.put("status", reason == null ? "SUCCESS" : "WARN");
            if (reason != null)
                status.put("reason", reason);
        } catch (CertificateException | InvalidNameException exception) {
            status.put("status", "WARN");
            status.put("reason", "kubeProxyCertificateInvalid");
        }
        return status;
    }

    private boolean containerRunning(JsonNode pod) {
        boolean declared = false;
        for (JsonNode value : requiredArray(pod.path("spec"), "containers")) {
            if (container.equals(optionalText(value, "name")))
                declared = true;
        }
        if (!declared)
            return false;

        for (JsonNode value : optionalArray(pod.path("status"), "containerStatuses")) {
            if (container.equals(optionalText(value, "name")) && value.path("state").path("running").isObject())
                return true;
        }
        return false;
    }

    private static X509Certificate certificate(byte[] pem) throws CertificateException, InvalidNameException {
        X509Certificate selected = null;
        for (var value : CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem))) {
            if (!(value instanceof X509Certificate certificate) || certificate.getBasicConstraints() >= 0)
                continue;

            boolean matches = new LdapName(certificate.getSubjectX500Principal().getName()).getRdns().stream()
                    .anyMatch(rdn -> "CN".equalsIgnoreCase(rdn.getType()) && "system:kube-proxy".equals(rdn.getValue()));
            if (!matches)
                continue;

            if (selected != null)
                throw new CertificateException("ambiguousCertificate");

            selected = certificate;
        }
        if (selected == null)
            throw new CertificateException("certificateIdentityMissing");

        return selected;
    }

    private static Map<String, Object> nodeWarning(String node, String reason) {
        return Map.of("node", node, "status", "WARN", "reason", reason);
    }

    private AlertResult failedInspection(List<String> discoveredNodes, List<Map<String, Object>> results, String reason) {
        Set<Object> inspected = new LinkedHashSet<>();
        results.forEach(node -> inspected.add(node.get("node")));
        for (String node : discoveredNodes) {
            if (!inspected.contains(node))
                results.add(nodeWarning(node, reason));
        }
        return result(results, reason);
    }

    private AlertResult result(List<Map<String, Object>> nodes, String reason) {
        long warning = nodes.stream().filter(node -> "WARN".equals(node.get("status"))).count();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("totalNodes", nodes.size());
        status.put("healthyNodes", nodes.size() - warning);
        status.put("warningNodes", warning);
        status.put("warningDays", warningDays);
        status.put("nodes", List.copyOf(nodes));
        if (reason != null)
            status.put("reason", reason);

        return reason != null || warning > 0 ? AlertResult.warn(status) : AlertResult.success(status);
    }

    // The kubectl boundary is self-contained because workers compile one source unit.
    private void validateKubeconfig(KubectlSession kubectl) throws Exception {
        CommandResult result = kubectl.run(List.of("config", "view", "--raw", "--minify", JSON_OUTPUT_ARGUMENT));
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
        String clusterReference = requiredText(contextValue, CLUSTER_FIELD);
        String userReference = optionalText(contextValue, "user");
        JsonNode clusterEntry = namedEntry(clusters, clusterReference, "kubeconfigClusterReferenceInvalid");
        JsonNode userEntry = userReference == null ? null : namedEntry(users, userReference, "kubeconfigUserReferenceInvalid");
        JsonNode cluster = requiredObject(clusterEntry, CLUSTER_FIELD);
        rejectPath(cluster, "certificate-authority");
        if (userEntry != null) {
            JsonNode user = requiredObject(userEntry, "user");
            rejectField(user, "exec");
            rejectField(user, "auth-provider");
            rejectPath(user, "tokenFile");
            rejectPath(user, "client-key");
            rejectPath(user, "client-certificate");
        }

        normalizedServer(requiredText(cluster, "server"));
        String ca = optionalText(cluster, "certificate-authority-data");

        try {
            if (ca != null)
                Base64.getMimeDecoder().decode(ca);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("kubeconfigCaInvalid");
        }
        kubectl.replaceKubeconfig(minimalKubeconfig(contextName, clusterReference, cluster, userReference, userEntry));
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
        sanitizedCluster.set(CLUSTER_FIELD, clusterValue);

        ObjectNode sanitizedContext = sanitized.putArray("contexts").addObject();
        sanitizedContext.put("name", contextName);
        ObjectNode contextValue = sanitizedContext.putObject("context");
        contextValue.put(CLUSTER_FIELD, clusterReference);
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

    @FunctionalInterface
    interface KubectlFactory {
        KubectlSession open(KubeconfigCredentials credentials, String context, int timeoutSeconds) throws Exception;
    }

    interface KubectlSession extends AutoCloseable {
        CommandResult run(List<String> arguments) throws Exception;
        void replaceKubeconfig(byte[] value) throws Exception;
        @Override void close() throws Exception;
    }

    record CommandResult(int exitCode, byte[] stdout, String stderrCategory, boolean timedOut, boolean outputLimitExceeded) {
        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof CommandResult that
                    && exitCode == that.exitCode
                    && timedOut == that.timedOut
                    && outputLimitExceeded == that.outputLimitExceeded
                    && Objects.equals(stderrCategory, that.stderrCategory)
                    && Arrays.equals(stdout, that.stdout);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(exitCode, stderrCategory, timedOut, outputLimitExceeded) + Arrays.hashCode(stdout);
        }

        @Override
        public String toString() {
            return "CommandResult[exitCode=" + exitCode + ", stdoutLength=" + (stdout == null ? null : stdout.length) + ", stderrCategory=" + stderrCategory + ", timedOut=" + timedOut + ", outputLimitExceeded=" + outputLimitExceeded + "]";
        }

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
        private static final byte[] KUBERC_CONTENT = """
                apiVersion: kubectl.config.k8s.io/v1beta1
                kind: Preference
                credentialPluginPolicy: DenyAll
                """.getBytes(StandardCharsets.UTF_8);

        private final Path directory;
        private final Path kubeconfig;
        private final Path kuberc;
        private final Path home;
        private final Path executable;
        private final String context;
        private final int timeoutSeconds;
        private volatile Process activeProcess;

        private ProcessKubectlSession(Path directory, Path kubeconfig, Path kuberc, Path home, Path executable, String context, int timeoutSeconds) {
            this.directory = directory;
            this.kubeconfig = kubeconfig;
            this.kuberc = kuberc;
            this.home = home;
            this.executable = executable;
            this.context = context;
            this.timeoutSeconds = timeoutSeconds;
        }

        static ProcessKubectlSession open(KubeconfigCredentials credentials, String context, int timeoutSeconds) throws IOException {
            return open(credentials, context, timeoutSeconds, Path.of("/usr/local/bin/kubectl"));
        }

        static ProcessKubectlSession open(KubeconfigCredentials credentials, String context, int timeoutSeconds, Path executable) throws IOException {
            Path directory = Files.createTempDirectory("alertify-kubernetes-", PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            try {
                Path home = Files.createDirectory(directory.resolve("home"), PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
                Path kubeconfig = createFile(directory.resolve("kubeconfig"), credentials.kubeconfig().getBytes(StandardCharsets.UTF_8));
                Path kuberc = createFile(directory.resolve("kuberc"), KUBERC_CONTENT);
                return new ProcessKubectlSession(directory, kubeconfig, kuberc, home, executable, context, timeoutSeconds);
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
            long deadlineNanos = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
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
                    throw new IOException("kubectlOutputReadFailed");

                throw new IOException("kubectlOutputReadFailed");
            } catch (TimeoutException exception) {
                throw new OperationalException("kubectlTimeout");
            }
        }

        private static String classify(byte[] stderr) {
            String value = new String(stderr, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (value.contains("notfound") || value.contains("not found"))
                return "kubeProxyResourceNotFound";

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
}
