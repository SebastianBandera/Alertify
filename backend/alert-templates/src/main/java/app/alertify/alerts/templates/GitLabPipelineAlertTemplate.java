package app.alertify.alerts.templates;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.lib.Repository;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;
import app.alertify.worker.contract.GitCredentials;
import app.alertify.worker.contract.GitProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the latest GitLab pipeline that actually ran for each configured
 * branch. Skipped, manual and scheduled pipelines are transparent: evaluation
 * walks backwards until it finds an executed pipeline. Same-project child
 * pipelines are inspected recursively because GitLab does not necessarily
 * mirror their result onto the parent pipeline.
 */
@AlertTemplate(
    nameKey = "alerts.template.gitLabPipeline.name",
    descriptionKey = "alerts.template.gitLabPipeline.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.git", color = "#F05032"),
    sourcePath = "app/alertify/alerts/templates/GitLabPipelineAlertTemplate.java"
)
public final class GitLabPipelineAlertTemplate implements AlertEvaluator {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_HISTORY_PAGES = 100;
    private static final int MAX_BRANCHES = 10;
    private static final int MAX_REPORTED_IGNORED_PIPELINES = 10;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_ACTIVE_MINUTES = 525_600;
    private static final int MAX_PIPELINE_AGE_HOURS = 87_600;
    private static final int MAX_TIMEOUT_SECONDS = 3_600;
    private static final int MAX_CHILD_DEPTH = 2;
    private static final Set<String> IGNORED_STATUSES = Set.of("skipped", "manual", "scheduled");
    private static final Set<String> ACTIVE_STATUSES = Set.of(
        "created", "waiting_for_resource", "preparing", "waiting_for_callback", "pending", "running"
    );
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AlertParameter(
        labelKey = "alerts.template.gitLabPipeline.credentials",
        descriptionKey = "alerts.template.gitLabPipeline.credentialsDescription",
        bindingAllowed = true,
        order = 1,
        allowedSources = { AlertParameterSource.SECRET },
        allowedSecretValueTypes = "GIT_SECRET"
    )
    private final GitCredentials credentials;

    @AlertParameter(
        labelKey = "alerts.template.gitLabPipeline.project",
        descriptionKey = "alerts.template.gitLabPipeline.projectDescription",
        order = 2
    )
    private final String project;

    @AlertParameter(
        labelKey = "alerts.template.gitLabPipeline.branchesJson",
        descriptionKey = "alerts.template.gitLabPipeline.branchesJsonDescription",
        defaultValue = "[\"main\"]",
        multiline = true,
        order = 3
    )
    private final String branchesJson;

    @AlertParameter(
        labelKey = "alerts.template.gitLabPipeline.checkActiveDuration",
        descriptionKey = "alerts.template.gitLabPipeline.checkActiveDurationDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "false",
        order = 4
    )
    private final boolean checkActiveDuration;

    @AlertParameter(
        labelKey = "alerts.template.gitLabPipeline.maxActiveMinutes",
        descriptionKey = "alerts.template.gitLabPipeline.maxActiveMinutesDescription",
        defaultValue = "60",
        order = 5
    )
    private final int maxActiveMinutes;

    @AlertParameter(
        labelKey = "alerts.template.gitLabPipeline.checkFreshness",
        descriptionKey = "alerts.template.gitLabPipeline.checkFreshnessDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "false",
        order = 6
    )
    private final boolean checkFreshness;

    @AlertParameter(
        labelKey = "alerts.template.gitLabPipeline.maxPipelineAgeHours",
        descriptionKey = "alerts.template.gitLabPipeline.maxPipelineAgeHoursDescription",
        defaultValue = "24",
        order = 7
    )
    private final int maxPipelineAgeHours;

    @AlertParameter(
        labelKey = "alerts.template.gitLabPipeline.timeout",
        descriptionKey = "alerts.template.gitLabPipeline.timeoutDescription",
        options = { "5", "10", "30", "60" },
        defaultValue = "10",
        order = 8
    )
    private final int timeoutSeconds;

    private final List<String> branches;
    private final URI apiBaseUri;
    private final Clock clock;
    private final HttpClient client;

    public GitLabPipelineAlertTemplate(GitCredentials credentials, String project, String branchesJson, boolean checkActiveDuration, int maxActiveMinutes, boolean checkFreshness, int maxPipelineAgeHours, int timeoutSeconds) {
        this(credentials, project, branchesJson, checkActiveDuration, maxActiveMinutes, checkFreshness, maxPipelineAgeHours, timeoutSeconds, apiBaseUri(credentials), Clock.systemUTC());
    }

    GitLabPipelineAlertTemplate(
        GitCredentials credentials,
        String project,
        String branchesJson,
        boolean checkActiveDuration,
        int maxActiveMinutes,
        boolean checkFreshness,
        int maxPipelineAgeHours,
        int timeoutSeconds,
        URI apiBaseUri,
        Clock clock
    ) {
        if (credentials == null) {
            throw new IllegalArgumentException("credentials must not be null");
        }
        if (maxActiveMinutes <= 0 || maxActiveMinutes > MAX_ACTIVE_MINUTES) {
            throw new IllegalArgumentException("maxActiveMinutes must be between 1 and " + MAX_ACTIVE_MINUTES);
        }
        if (maxPipelineAgeHours <= 0 || maxPipelineAgeHours > MAX_PIPELINE_AGE_HOURS) {
            throw new IllegalArgumentException("maxPipelineAgeHours must be between 1 and " + MAX_PIPELINE_AGE_HOURS);
        }
        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
        }

        this.credentials = credentials;
        this.project = validateProject(project);
        this.branchesJson = requireText(branchesJson, "branchesJson");
        this.branches = parseBranches(this.branchesJson);
        this.checkActiveDuration = checkActiveDuration;
        this.maxActiveMinutes = maxActiveMinutes;
        this.checkFreshness = checkFreshness;
        this.maxPipelineAgeHours = maxPipelineAgeHours;
        this.timeoutSeconds = timeoutSeconds;
        this.apiBaseUri = validateApiBaseUri(apiBaseUri);
        this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) throws Exception {
        Instant checkedAt = clock.instant();
        Map<String, Object> statusMessage = baseStatusMessage(checkedAt);
        if (credentials.provider() != GitProvider.GITLAB) {
            statusMessage.put("failureReason", "unsupported_provider");
            context.setState(state(List.of(), "unsupported_provider"));
            return AlertResult.warn(statusMessage);
        }

        List<Map<String, Object>> branchResults = new ArrayList<>();
        List<String> warningBranches = new ArrayList<>();
        for (String branch : branches) {
            BranchEvaluation evaluation;
            try {
                evaluation = evaluateBranch(branch, checkedAt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            } catch (ApiFailure exception) {
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("branch", branch);
                failed.put("warning", true);
                failed.put("failureReason", exception.reason());
                if (exception.statusCode() != null) {
                    failed.put("statusCode", exception.statusCode());
                }
                evaluation = new BranchEvaluation(failed, true);
            }
            branchResults.add(evaluation.message());
            if (evaluation.warning()) {
                warningBranches.add(branch);
            }
        }

        statusMessage.put("branchResults", branchResults);
        statusMessage.put("warningBranches", warningBranches);
        context.setState(state(warningBranches, null));
        return warningBranches.isEmpty() ? AlertResult.success(statusMessage) : AlertResult.warn(statusMessage);
    }

    private BranchEvaluation evaluateBranch(String branch, Instant checkedAt) throws ApiFailure, InterruptedException {
        PipelineSearch search = findExecutedPipeline(branch);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", branch);
        result.put("ignoredPipelineCount", search.ignoredCount());
        result.put("ignoredPipelines", search.reportedIgnored());

        if (search.pipeline() == null) {
            if (!branchExists(branch)) {
                result.put("warning", true);
                result.put("failureReason", "branch_not_found");
                return new BranchEvaluation(result, true);
            }

            result.put("warning", false);
            result.put("outcome", "no_executed_pipeline");
            return new BranchEvaluation(result, false);
        }

        Pipeline pipeline = search.pipeline();
        result.put("pipeline", pipeline.message());
        List<String> warnings = new ArrayList<>();
        addPipelineWarnings(pipeline, checkedAt, true, warnings);

        ChildrenEvaluation children = inspectChildren(pipeline, 0, new HashSet<>(), checkedAt);
        result.put("childPipelines", children.children());
        result.put("externalDownstreamCount", children.externalDownstreamCount());
        if (children.warning()) {
            warnings.add("child_pipeline_not_success");
        }

        result.put("warnings", warnings);
        result.put("warning", !warnings.isEmpty());
        return new BranchEvaluation(result, !warnings.isEmpty());
    }

    private PipelineSearch findExecutedPipeline(String branch) throws ApiFailure, InterruptedException {
        int ignoredCount = 0;
        List<Map<String, Object>> reportedIgnored = new ArrayList<>();
        for (int page = 1; page <= MAX_HISTORY_PAGES; page++) {
            String path = projectPath() + "/pipelines?ref=" + encode(branch) + "&order_by=id&sort=desc&per_page=" + PAGE_SIZE + "&page=" + page;
            JsonNode root = requestJson(path, false);
            if (!root.isArray()) {
                throw new ApiFailure("invalid_response");
            }

            int pageCount = 0;
            for (JsonNode node : root) {
                pageCount++;
                Pipeline pipeline = parsePipeline(node);
                if (!IGNORED_STATUSES.contains(pipeline.status())) {
                    return new PipelineSearch(pipeline, ignoredCount, List.copyOf(reportedIgnored));
                }

                ignoredCount++;
                if (reportedIgnored.size() < MAX_REPORTED_IGNORED_PIPELINES) {
                    reportedIgnored.add(pipeline.message());
                }
            }
            if (pageCount < PAGE_SIZE) {
                return new PipelineSearch(null, ignoredCount, List.copyOf(reportedIgnored));
            }
        }
        throw new ApiFailure("history_limit_exceeded");
    }

    private boolean branchExists(String branch) throws ApiFailure, InterruptedException {
        JsonNode response = requestJson(projectPath() + "/repository/branches/" + encode(branch), true);
        return response != null;
    }

    private ChildrenEvaluation inspectChildren(Pipeline parent, int depth, Set<Long> visited, Instant checkedAt) throws ApiFailure, InterruptedException {
        if (depth >= MAX_CHILD_DEPTH) {
            return new ChildrenEvaluation(List.of(), false, 0);
        }

        List<JsonNode> triggerJobs = triggerJobs(parent.id());
        List<Map<String, Object>> children = new ArrayList<>();
        boolean warning = false;
        int externalDownstreamCount = 0;
        for (JsonNode triggerJob : triggerJobs) {
            JsonNode downstream = triggerJob.get("downstream_pipeline");
            if (downstream == null || downstream.isNull()) {
                continue;
            }

            long childId = requiredLong(downstream, "id");
            Pipeline child = currentProjectPipeline(childId);
            if (child == null || !"parent_pipeline".equals(child.source())) {
                externalDownstreamCount++;
                continue;
            }
            if (!visited.add(child.id())) {
                Map<String, Object> cyclic = child.message();
                cyclic.put("ignoredReason", "already_visited");
                children.add(cyclic);
                continue;
            }

            Map<String, Object> childMessage = child.message();
            if (IGNORED_STATUSES.contains(child.status())) {
                childMessage.put("ignored", true);
                children.add(childMessage);
                continue;
            }

            List<String> childWarnings = new ArrayList<>();
            addPipelineWarnings(child, checkedAt, false, childWarnings);
            ChildrenEvaluation nested = inspectChildren(child, depth + 1, visited, checkedAt);
            childMessage.put("childPipelines", nested.children());
            childMessage.put("externalDownstreamCount", nested.externalDownstreamCount());
            if (nested.warning()) {
                childWarnings.add("child_pipeline_not_success");
            }
            childMessage.put("warnings", childWarnings);
            childMessage.put("warning", !childWarnings.isEmpty());
            children.add(childMessage);
            warning |= !childWarnings.isEmpty();
            externalDownstreamCount += nested.externalDownstreamCount();
        }
        return new ChildrenEvaluation(List.copyOf(children), warning, externalDownstreamCount);
    }

    private List<JsonNode> triggerJobs(long pipelineId) throws ApiFailure, InterruptedException {
        String base = projectPath() + "/pipelines/" + pipelineId + "/trigger_jobs";
        JsonNode first = requestJson(base + "?per_page=" + PAGE_SIZE + "&page=1", true);
        if (first == null) {
            base = projectPath() + "/pipelines/" + pipelineId + "/bridges";
            first = requestJson(base + "?per_page=" + PAGE_SIZE + "&page=1", false);
        }
        if (!first.isArray()) {
            throw new ApiFailure("invalid_response");
        }

        List<JsonNode> jobs = new ArrayList<>();
        addNodes(jobs, first);
        for (int page = 2; first.size() == PAGE_SIZE && page <= MAX_HISTORY_PAGES; page++) {
            JsonNode next = requestJson(base + "?per_page=" + PAGE_SIZE + "&page=" + page, false);
            if (!next.isArray()) {
                throw new ApiFailure("invalid_response");
            }
            addNodes(jobs, next);
            first = next;
        }
        if (first.size() == PAGE_SIZE) {
            throw new ApiFailure("child_limit_exceeded");
        }
        return List.copyOf(jobs);
    }

    private Pipeline currentProjectPipeline(long pipelineId) throws ApiFailure, InterruptedException {
        JsonNode response = requestJson(projectPath() + "/pipelines/" + pipelineId, true);
        return response == null ? null : parsePipeline(response);
    }

    private void addPipelineWarnings(Pipeline pipeline, Instant checkedAt, boolean checkPipelineFreshness, List<String> warnings) {
        if ("success".equals(pipeline.status())) {
            if (checkPipelineFreshness && checkFreshness && ageHours(pipeline.createdAt(), checkedAt) > maxPipelineAgeHours) {
                warnings.add("pipeline_stale");
            }
            return;
        }

        if (ACTIVE_STATUSES.contains(pipeline.status())) {
            if (checkActiveDuration && ageMinutes(pipeline.createdAt(), checkedAt) > maxActiveMinutes) {
                warnings.add("pipeline_active_too_long");
            }
            return;
        }

        warnings.add("pipeline_not_success");
    }

    private JsonNode requestJson(String path, boolean allowNotFound) throws ApiFailure, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(apiBaseUri.resolve(path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + credentials.token())
            .header("User-Agent", "Alertify/GitLabPipeline")
            .GET()
            .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (allowNotFound && response.statusCode() == 404) {
                close(response.body());
                return null;
            }
            if (response.statusCode() != 200) {
                close(response.body());
                throw httpFailure(response.statusCode());
            }

            byte[] body = readBody(response);
            try {
                JsonNode parsed = JSON.readTree(body);
                if (parsed == null) {
                    throw new ApiFailure("invalid_response");
                }
                return parsed;
            } catch (RuntimeException exception) {
                throw new ApiFailure("invalid_response");
            }
        } catch (HttpTimeoutException exception) {
            throw new ApiFailure("timeout");
        } catch (ResponseTooLargeException exception) {
            throw new ApiFailure("response_too_large");
        } catch (IOException exception) {
            throw new ApiFailure("connection_failure");
        }
    }

    private static byte[] readBody(HttpResponse<InputStream> response) throws IOException {
        long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (contentLength > MAX_RESPONSE_BYTES) {
            close(response.body());
            throw new ResponseTooLargeException();
        }

        try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new ResponseTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static ApiFailure httpFailure(int statusCode) {
        String reason = switch (statusCode) {
            case 401, 403 -> "auth_failed";
            case 404 -> "not_found";
            case 429 -> "rate_limited";
            default -> "unexpected_http_status";
        };
        return new ApiFailure(reason, statusCode);
    }

    private static Pipeline parsePipeline(JsonNode node) throws ApiFailure {
        if (node == null || !node.isObject()) {
            throw new ApiFailure("invalid_response");
        }
        return new Pipeline(
            requiredLong(node, "id"),
            requiredText(node, "status").toLowerCase(Locale.ROOT),
            optionalText(node, "ref"),
            optionalText(node, "sha"),
            requiredInstant(node, "created_at"),
            optionalInstant(node, "updated_at"),
            optionalText(node, "web_url"),
            optionalText(node, "source")
        );
    }

    private Map<String, Object> baseStatusMessage(Instant checkedAt) {
        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("provider", credentials.provider().name());
        statusMessage.put("host", credentials.host());
        statusMessage.put("project", project);
        statusMessage.put("branches", branches);
        statusMessage.put("checkActiveDuration", checkActiveDuration);
        statusMessage.put("maxActiveMinutes", maxActiveMinutes);
        statusMessage.put("checkFreshness", checkFreshness);
        statusMessage.put("maxPipelineAgeHours", maxPipelineAgeHours);
        statusMessage.put("timeoutSeconds", timeoutSeconds);
        statusMessage.put("checkedAt", checkedAt.toString());
        return statusMessage;
    }

    private String projectPath() {
        return "projects/" + encode(project);
    }

    private String state(List<String> warningBranches, String failureReason) {
        String base = "project=" + project + ";branches=" + String.join(",", branches);
        if (failureReason != null) {
            return base + ";failure=" + failureReason;
        }
        return base + ";warningBranches=" + String.join(",", warningBranches);
    }

    private static URI apiBaseUri(GitCredentials credentials) {
        if (credentials == null) {
            throw new IllegalArgumentException("credentials must not be null");
        }
        return URI.create("https://" + credentials.host() + "/api/v4/");
    }

    private static URI validateApiBaseUri(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("apiBaseUri must be an absolute HTTP or HTTPS URI without credentials, query or fragment");
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("apiBaseUri must use HTTP or HTTPS");
        }

        String value = uri.toString();
        return value.endsWith("/") ? uri : URI.create(value + "/");
    }

    private static String validateProject(String value) {
        String project = requireText(value, "project").trim();
        if (project.startsWith("/") || project.endsWith("/") || project.contains("//") || project.contains("?") || project.contains("#") || project.contains("://")) {
            throw new IllegalArgumentException("project must be a numeric ID or a namespace/project path");
        }
        return project;
    }

    private static List<String> parseBranches(String configured) {
        JsonNode root;
        try {
            root = JSON.readTree(configured);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("branchesJson must be a JSON array of branch names", exception);
        }
        if (root == null || !root.isArray() || root.isEmpty() || root.size() > MAX_BRANCHES) {
            throw new IllegalArgumentException("branchesJson must contain between 1 and " + MAX_BRANCHES + " branch names");
        }

        LinkedHashSet<String> branches = new LinkedHashSet<>();
        for (JsonNode node : root) {
            if (!node.isString()) {
                throw new IllegalArgumentException("branchesJson must contain only strings");
            }

            String branch = node.stringValue().trim();
            if (!Repository.isValidRefName("refs/heads/" + branch)) {
                throw new IllegalArgumentException("Invalid branch name: " + branch);
            }
            if (!branches.add(branch)) {
                throw new IllegalArgumentException("branchesJson must not contain duplicate branch names");
            }
        }
        return List.copyOf(branches);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void addNodes(List<JsonNode> target, JsonNode array) {
        for (JsonNode node : array) {
            target.add(node);
        }
    }

    private static long requiredLong(JsonNode node, String field) throws ApiFailure {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new ApiFailure("invalid_response");
        }
        return value.longValue();
    }

    private static String requiredText(JsonNode node, String field) throws ApiFailure {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new ApiFailure("invalid_response");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) throws ApiFailure {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new ApiFailure("invalid_response");
        }
        return value.stringValue();
    }

    private static Instant requiredInstant(JsonNode node, String field) throws ApiFailure {
        Instant value = optionalInstant(node, field);
        if (value == null) {
            throw new ApiFailure("invalid_response");
        }
        return value;
    }

    private static Instant optionalInstant(JsonNode node, String field) throws ApiFailure {
        String value = optionalText(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiFailure("invalid_response");
        }
    }

    private static long ageMinutes(Instant createdAt, Instant checkedAt) {
        return Math.max(0, Duration.between(createdAt, checkedAt).toMinutes());
    }

    private static long ageHours(Instant createdAt, Instant checkedAt) {
        return Math.max(0, Duration.between(createdAt, checkedAt).toHours());
    }

    private static void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The response is already being discarded.
        }
    }

    private record BranchEvaluation(Map<String, Object> message, boolean warning) {}

    private record ChildrenEvaluation(List<Map<String, Object>> children, boolean warning, int externalDownstreamCount) {}

    private record PipelineSearch(Pipeline pipeline, int ignoredCount, List<Map<String, Object>> reportedIgnored) {}

    private record Pipeline(long id, String status, String ref, String sha, Instant createdAt, Instant updatedAt, String webUrl, String source) {
        private Map<String, Object> message() {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", id);
            message.put("status", status);
            if (ref != null) {
                message.put("ref", ref);
            }
            if (sha != null) {
                message.put("sha", sha);
            }
            message.put("createdAt", createdAt.toString());
            if (updatedAt != null) {
                message.put("updatedAt", updatedAt.toString());
            }
            if (webUrl != null) {
                message.put("webUrl", webUrl);
            }
            if (source != null) {
                message.put("source", source);
            }
            return message;
        }
    }

    private static final class ApiFailure extends Exception {
        private final String reason;
        private final Integer statusCode;

        private ApiFailure(String reason) {
            this(reason, null);
        }

        private ApiFailure(String reason, Integer statusCode) {
            super(reason);
            this.reason = reason;
            this.statusCode = statusCode;
        }

        private String reason() {
            return reason;
        }

        private Integer statusCode() {
            return statusCode;
        }
    }

    private static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
