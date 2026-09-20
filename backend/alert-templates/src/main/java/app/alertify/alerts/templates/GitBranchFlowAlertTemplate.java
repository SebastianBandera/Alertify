package app.alertify.alerts.templates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FileUtils;

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
 * Watches a promotion flow of branches (for example develop → preprod →
 * production) in a remote Git repository and warns when the last branch lags
 * behind the previous one for too long, when promoting the previous branch
 * would conflict, or when the last branch carries commits that never went
 * through the earlier branches. Each of the three checks can be switched off
 * individually; a disabled check is skipped entirely and contributes nothing
 * to the status message or the state. The repository is fetched into a
 * disposable bare workspace that is removed after every evaluation; the token
 * is only handed to JGit's credentials provider and never appears in URLs,
 * state or status messages.
 */
@AlertTemplate(
    nameKey = "alerts.template.gitBranchFlow.name",
    descriptionKey = "alerts.template.gitBranchFlow.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.git", color = "#F05032"),
    sourcePath = "app/alertify/alerts/templates/GitBranchFlowAlertTemplate.java"
)
public final class GitBranchFlowAlertTemplate implements AlertEvaluator {

    private static final int MAX_BRANCHES = 10;
    private static final int MAX_STALE_DAYS = 3_650;
    private static final int MAX_TIMEOUT_SECONDS = 3_600;
    private static final int MAX_REPORTED_COMMITS = 10;
    private static final Pattern URL_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AlertParameter(
        labelKey = "alerts.template.gitBranchFlow.credentials",
        descriptionKey = "alerts.template.gitBranchFlow.credentialsDescription",
        bindingAllowed = true,
        order = 1,
        allowedSources = { AlertParameterSource.SECRET },
        allowedSecretValueTypes = "GIT_SECRET"
    )
    private final GitCredentials credentials;

    @AlertParameter(
        labelKey = "alerts.template.gitBranchFlow.repository",
        descriptionKey = "alerts.template.gitBranchFlow.repositoryDescription",
        order = 2
    )
    private final String repository;

    @AlertParameter(
        labelKey = "alerts.template.gitBranchFlow.branchFlowJson",
        descriptionKey = "alerts.template.gitBranchFlow.branchFlowJsonDescription",
        defaultValue = "[\"develop\", \"preprod\", \"main\"]",
        multiline = true,
        order = 3
    )
    private final String branchFlowJson;

    @AlertParameter(
        labelKey = "alerts.template.gitBranchFlow.checkDeploymentDelay",
        descriptionKey = "alerts.template.gitBranchFlow.checkDeploymentDelayDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 4
    )
    private final boolean checkDeploymentDelay;

    @AlertParameter(
        labelKey = "alerts.template.gitBranchFlow.staleDaysThreshold",
        descriptionKey = "alerts.template.gitBranchFlow.staleDaysThresholdDescription",
        options = { "1", "3", "7", "14", "30" },
        defaultValue = "3",
        order = 5
    )
    private final int staleDaysThreshold;

    @AlertParameter(
        labelKey = "alerts.template.gitBranchFlow.checkMergeConflict",
        descriptionKey = "alerts.template.gitBranchFlow.checkMergeConflictDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 6
    )
    private final boolean checkMergeConflict;

    @AlertParameter(
        labelKey = "alerts.template.gitBranchFlow.checkCommitsOutsideFlow",
        descriptionKey = "alerts.template.gitBranchFlow.checkCommitsOutsideFlowDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 7
    )
    private final boolean checkCommitsOutsideFlow;

    @AlertParameter(
        labelKey = "alerts.template.gitBranchFlow.timeout",
        descriptionKey = "alerts.template.gitBranchFlow.timeoutDescription",
        options = { "30", "60", "120", "300" },
        defaultValue = "60",
        order = 8
    )
    private final int timeoutSeconds;

    private final List<String> branchFlow;

    public GitBranchFlowAlertTemplate(GitCredentials credentials, String repository, String branchFlowJson, boolean checkDeploymentDelay, int staleDaysThreshold, boolean checkMergeConflict, boolean checkCommitsOutsideFlow, int timeoutSeconds) {
        if (credentials == null)
            throw new IllegalArgumentException("credentials must not be null");

        if (staleDaysThreshold < 0 || staleDaysThreshold > MAX_STALE_DAYS)
            throw new IllegalArgumentException("staleDaysThreshold must be between 0 and " + MAX_STALE_DAYS);

        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS)
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);

        this.credentials = credentials;
        this.repository = validateRepository(repository);
        this.branchFlowJson = requireText(branchFlowJson, "branchFlowJson");
        this.branchFlow = parseBranchFlow(this.branchFlowJson);
        this.checkDeploymentDelay = checkDeploymentDelay;
        this.staleDaysThreshold = staleDaysThreshold;
        this.checkMergeConflict = checkMergeConflict;
        this.checkCommitsOutsideFlow = checkCommitsOutsideFlow;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) throws Exception {
        Instant checkedAt = Instant.now();
        Map<String, Object> statusMessage = baseStatusMessage(checkedAt);
        if (credentials.provider() == GitProvider.OTHER) {
            statusMessage.put("failureReason", "unsupported_provider");
            context.setState(state("failureReason=unsupported_provider"));
            return AlertResult.warn(statusMessage);
        }

        Path workspace = Files.createTempDirectory("alertify-git-");
        long startedNanos = System.nanoTime();
        try (Git git = Git.init().setBare(true).setDirectory(workspace.toFile()).call()) {
            try {
                fetchBranches(git);
            } catch (GitAPIException exception) {
                String failureReason = failureReason(exception);
                statusMessage.put("fetchMs", elapsedMillis(startedNanos));
                statusMessage.put("failureReason", failureReason);
                statusMessage.put("failureMessage", sanitize(exception.getMessage()));
                context.setState(state("failureReason=" + failureReason));
                return AlertResult.warn(statusMessage);
            }
            statusMessage.put("fetchMs", elapsedMillis(startedNanos));

            Repository repo = git.getRepository();
            List<RevCommit> heads = resolveHeads(repo);
            List<String> warnings = new ArrayList<>();
            List<String> stateSegments = new ArrayList<>();

            if (checkDeploymentDelay) {
                List<Map<String, Object>> transitions = new ArrayList<>();
                boolean delayed = false;
                for (int index = 0; index < heads.size() - 1; index++) {
                    boolean last = index == heads.size() - 2;
                    Map<String, Object> transition = describeTransition(repo, index, heads.get(index), heads.get(index + 1), checkedAt, last);
                    if (last && Boolean.TRUE.equals(transition.get("delayed")))
                        delayed = true;

                    transitions.add(transition);
                }
                statusMessage.put("transitions", transitions);
                if (delayed)
                    warnings.add("deployment_delayed");

                stateSegments.add("delayed=" + delayed);
            }

            if (checkMergeConflict) {
                RevCommit previous = heads.get(heads.size() - 2);
                RevCommit target = heads.get(heads.size() - 1);
                Map<String, Object> merge = describeMerge(repo, previous, target);
                statusMessage.put("merge", merge);
                boolean conflict = Boolean.TRUE.equals(merge.get("conflict"));
                if (conflict)
                    warnings.add("merge_conflict");

                stateSegments.add("conflict=" + conflict);
            }

            if (checkCommitsOutsideFlow) {
                List<Map<String, Object>> outsideFlow = describeCommitsOutsideFlow(repo, heads);
                statusMessage.put("commitsOutsideFlow", outsideFlow);
                int outsideFlowCount = outsideFlow.stream().mapToInt(entry -> (Integer) entry.get("count")).sum();
                if (outsideFlowCount > 0)
                    warnings.add("commits_outside_flow");

                stateSegments.add("commitsOutsideFlow=" + outsideFlowCount);
            }

            statusMessage.put("warnings", warnings);
            statusMessage.put("totalMs", elapsedMillis(startedNanos));
            context.setState(state(String.join(";", stateSegments)));
            return warnings.isEmpty() ? AlertResult.success(statusMessage) : AlertResult.warn(statusMessage);
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private void fetchBranches(Git git) throws GitAPIException {
        List<RefSpec> refSpecs = new ArrayList<>();
        for (String branch : branchFlow)
            refSpecs.add(new RefSpec("+refs/heads/" + branch + ":refs/heads/" + branch));

        git.fetch()
            .setRemote(cloneUrl())
            .setRefSpecs(refSpecs)
            .setCredentialsProvider(new UsernamePasswordCredentialsProvider(authenticationUsername(), credentials.token()))
            .setTimeout(timeoutSeconds)
            .call();
    }

    private List<RevCommit> resolveHeads(Repository repo) throws IOException {
        List<RevCommit> heads = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            for (String branch : branchFlow) {
                ObjectId id = repo.resolve("refs/heads/" + branch);
                if (id == null)
                    throw new IllegalStateException("branch '" + branch + "' was not fetched");

                heads.add(walk.parseCommit(id));
            }
        }
        return heads;
    }

    /** Commits reachable from {@code previous} that {@code target} does not contain yet, i.e. pending promotion. */
    private Map<String, Object> describeTransition(Repository repo, int index, RevCommit previous, RevCommit target, Instant checkedAt, boolean last) throws IOException {
        List<RevCommit> pending = commitsOnlyIn(repo, previous, target);
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("from", branchFlow.get(index));
        transition.put("to", branchFlow.get(index + 1));
        transition.put("pendingCommits", pending.size());
        if (!pending.isEmpty()) {
            RevCommit oldest = pending.get(0);
            for (RevCommit commit : pending) {
                if (commit.getCommitTime() < oldest.getCommitTime())
                    oldest = commit;
            }
            Instant oldestAt = Instant.ofEpochSecond(oldest.getCommitTime());
            long ageDays = Duration.between(oldestAt, checkedAt).toDays();
            transition.put("oldestPendingCommit", describeCommit(oldest));
            transition.put("oldestPendingAgeDays", ageDays);
            if (last)
                transition.put("delayed", ageDays > staleDaysThreshold);
        } else if (last) {
            transition.put("delayed", false);
        }
        return transition;
    }

    /** Dry-run merge of {@code previous} into {@code target}, fully in memory; nothing is written to the workspace. */
    private static Map<String, Object> describeMerge(Repository repo, RevCommit previous, RevCommit target) throws IOException {
        ThreeWayMerger merger = MergeStrategy.RECURSIVE.newMerger(repo, true);
        boolean clean = merger.merge(target, previous);
        Map<String, Object> merge = new LinkedHashMap<>();
        merge.put("conflict", !clean);
        if (!clean && merger instanceof ResolveMerger resolveMerger)
            merge.put("conflictingPaths", limit(resolveMerger.getUnmergedPaths()));

        return merge;
    }

    /** Commits on the last branch that any earlier branch of the flow does not contain. */
    private List<Map<String, Object>> describeCommitsOutsideFlow(Repository repo, List<RevCommit> heads) throws IOException {
        RevCommit target = heads.get(heads.size() - 1);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < heads.size() - 1; index++) {
            List<RevCommit> missing = commitsOnlyIn(repo, target, heads.get(index));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("missingFrom", branchFlow.get(index));
            entry.put("count", missing.size());
            List<Map<String, Object>> commits = new ArrayList<>();
            for (RevCommit commit : missing) {
                if (commits.size() >= MAX_REPORTED_COMMITS)
                    break;

                commits.add(describeCommit(commit));
            }
            entry.put("commits", commits);
            result.add(entry);
        }
        return result;
    }

    private static List<RevCommit> commitsOnlyIn(Repository repo, RevCommit included, RevCommit excluded) throws IOException {
        List<RevCommit> commits = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(included));
            walk.markUninteresting(walk.parseCommit(excluded));
            for (RevCommit commit : walk)
                commits.add(commit);
        }
        return commits;
    }

    private static Map<String, Object> describeCommit(RevCommit commit) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("sha", commit.getName());
        description.put("message", commit.getShortMessage());
        description.put("committedAt", Instant.ofEpochSecond(commit.getCommitTime()).toString());
        return description;
    }

    private Map<String, Object> baseStatusMessage(Instant checkedAt) {
        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("provider", credentials.provider().name());
        statusMessage.put("host", credentials.host());
        statusMessage.put("repository", repository);
        statusMessage.put("branchFlow", branchFlow);
        statusMessage.put("checkDeploymentDelay", checkDeploymentDelay);
        statusMessage.put("staleDaysThreshold", staleDaysThreshold);
        statusMessage.put("checkMergeConflict", checkMergeConflict);
        statusMessage.put("checkCommitsOutsideFlow", checkCommitsOutsideFlow);
        statusMessage.put("timeoutSeconds", timeoutSeconds);
        statusMessage.put("checkedAt", checkedAt.toString());
        return statusMessage;
    }

    private String state(String suffix) {
        String base = "repository=" + repository + ";flow=" + String.join(">", branchFlow);
        return suffix.isEmpty() ? base : base + ";" + suffix;
    }

    private String cloneUrl() {
        if (URL_SCHEME.matcher(repository).matches())
            return repository;

        return "https://" + credentials.host() + "/" + repository + ".git";
    }

    /** Providers accept a token as the HTTPS password with a conventional placeholder user unless the secret names one. */
    private String authenticationUsername() {
        if (credentials.username() != null)
            return credentials.username();

        return switch (credentials.provider()) {
            case GITHUB -> "x-access-token";
            case GITLAB -> "oauth2";
            case BITBUCKET -> "x-token-auth";
            case OTHER -> "token";
        };
    }

    private static String failureReason(GitAPIException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("not authorized") || message.contains("authentication") || message.contains("401") || message.contains("403"))
            return "auth_failed";

        if (message.contains("available for fetch"))
            return "branch_not_found";

        if (message.contains("not found") || message.contains("404") || message.contains("does not appear to be a git repository"))
            return "repository_not_found";

        if (message.contains("timed out") || message.contains("timeout"))
            return "timeout";

        return "fetch_failed";
    }

    private String sanitize(String message) {
        if (message == null)
            return null;

        return message.replace(credentials.token(), "****");
    }

    private static void deleteWorkspace(Path workspace) {
        try {
            FileUtils.delete(workspace.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING | FileUtils.IGNORE_ERRORS);
        } catch (IOException ignored) {
            // Best effort: the workspace lives under the temporary directory and holds no secrets.
        }
    }

    private static String validateRepository(String value) {
        String repository = requireText(value, "repository");
        if (repository.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("repository must not contain whitespace");

        if (URL_SCHEME.matcher(repository).matches())
            return repository;

        if (repository.startsWith("/") || repository.endsWith("/") || !repository.contains("/") || repository.contains(".."))
            throw new IllegalArgumentException("repository must be owner/name or a full clone URL");

        return repository;
    }

    private static List<String> parseBranchFlow(String configured) {
        JsonNode root;
        try {
            root = JSON.readTree(configured);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("branchFlowJson must be valid JSON", exception);
        }
        if (!root.isArray() || root.size() < 2)
            throw new IllegalArgumentException("branchFlowJson must be a JSON array with at least two branches");

        if (root.size() > MAX_BRANCHES)
            throw new IllegalArgumentException("branchFlowJson must not list more than " + MAX_BRANCHES + " branches");

        List<String> branches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < root.size(); index++) {
            JsonNode value = root.get(index);
            if (!value.isString())
                throw new IllegalArgumentException("branchFlowJson item " + index + " must be a branch name");

            String branch = requireText(value.stringValue(), "branchFlowJson item " + index);
            if (!Repository.isValidRefName("refs/heads/" + branch))
                throw new IllegalArgumentException("branchFlowJson item " + index + " is not a valid branch name");

            if (!seen.add(branch))
                throw new IllegalArgumentException("branchFlowJson contains duplicate branch '" + branch + "'");

            branches.add(branch);
        }
        return List.copyOf(branches);
    }

    private static List<String> limit(List<String> values) {
        return values.size() <= MAX_REPORTED_COMMITS ? List.copyOf(values) : List.copyOf(values.subList(0, MAX_REPORTED_COMMITS));
    }

    private static String requireText(String value, String parameter) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(parameter + " must not be blank");

        return value.trim();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, System.nanoTime() - startedNanos) / 1_000_000;
    }
}
