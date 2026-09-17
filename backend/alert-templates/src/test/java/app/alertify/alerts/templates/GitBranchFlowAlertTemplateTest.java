package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.worker.contract.GitCredentials;
import app.alertify.worker.contract.GitProvider;

class GitBranchFlowAlertTemplateTest {

    private static final String TOKEN = "ghp_super-secret-token";
    private static final String FLOW = "[\"develop\",\"preprod\",\"main\"]";

    @TempDir
    Path remote;

    @Test
    void declaresLocalizedTemplateAndParameterMetadata() throws ReflectiveOperationException {
        AlertTemplate metadata = GitBranchFlowAlertTemplate.class.getAnnotation(AlertTemplate.class);

        assertEquals("alerts.template.gitBranchFlow.name", metadata.nameKey());
        assertEquals("app/alertify/alerts/templates/GitBranchFlowAlertTemplate.java", metadata.sourcePath());
        assertEquals(GitCredentials.class, GitBranchFlowAlertTemplate.class.getDeclaredField("credentials").getType());
        assertEquals(List.of("GIT_SECRET"), List.of(parameter("credentials").allowedSecretValueTypes()));
        assertEquals(2, parameter("repository").order());
        assertTrue(parameter("branchFlowJson").multiline());
        assertEquals("3", parameter("staleDaysThreshold").defaultValue());
        assertEquals("60", parameter("timeoutSeconds").defaultValue());
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        GitCredentials credentials = credentials(GitProvider.GITHUB);

        assertThrows(IllegalArgumentException.class, () -> new GitBranchFlowAlertTemplate(null, "o/r", FLOW, 3, 60));
        assertThrows(IllegalArgumentException.class, () -> new GitBranchFlowAlertTemplate(credentials, " ", FLOW, 3, 60));
        assertThrows(IllegalArgumentException.class, () -> new GitBranchFlowAlertTemplate(credentials, "no-slash", FLOW, 3, 60));
        assertThrows(IllegalArgumentException.class, () -> new GitBranchFlowAlertTemplate(credentials, "o/r", "[\"main\"]", 3, 60));
        assertThrows(IllegalArgumentException.class, () -> new GitBranchFlowAlertTemplate(credentials, "o/r", "[\"a\",\"a\"]", 3, 60));
        assertThrows(IllegalArgumentException.class, () -> new GitBranchFlowAlertTemplate(credentials, "o/r", "{\"a\":1}", 3, 60));
        assertThrows(IllegalArgumentException.class, () -> new GitBranchFlowAlertTemplate(credentials, "o/r", "[\"a\",\"bad name\"]", 3, 60));
        assertThrows(IllegalArgumentException.class, () -> new GitBranchFlowAlertTemplate(credentials, "o/r", FLOW, -1, 60));
        assertThrows(IllegalArgumentException.class, () -> new GitBranchFlowAlertTemplate(credentials, "o/r", FLOW, 3, 0));
    }

    @Test
    void warnsForUnsupportedProviderWithoutFetching() throws Exception {
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = new GitBranchFlowAlertTemplate(credentials(GitProvider.OTHER), "o/r", FLOW, 3, 60).evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals("unsupported_provider", result.statusMessage().get("failureReason"));
        assertTrue(context.getState().contains("failureReason=unsupported_provider"));
    }

    @Test
    void succeedsWhenEveryBranchIsAligned() throws Exception {
        try (Git git = Git.init().setInitialBranch("develop").setDirectory(remote.toFile()).call()) {
            commit(git, "app.txt", "v1", "initial", daysAgo(10));
            git.branchCreate().setName("preprod").call();
            git.branchCreate().setName("main").call();
        }
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = template().evaluate(context);

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals(List.of(), result.statusMessage().get("warnings"));
        List<Map<String, Object>> transitions = transitions(result);
        assertEquals(2, transitions.size());
        assertEquals(0, transitions.get(1).get("pendingCommits"));
        assertEquals(false, transitions.get(1).get("delayed"));
        assertEquals(false, merge(result).get("conflict"));
        assertTrue(context.getState().contains("delayed=false;conflict=false;commitsOutsideFlow=0"));
        assertNoTokenLeak(result, context);
    }

    @Test
    void warnsWhenTheLastBranchLagsBehindLongerThanTheThreshold() throws Exception {
        try (Git git = Git.init().setInitialBranch("develop").setDirectory(remote.toFile()).call()) {
            commit(git, "app.txt", "v1", "initial", daysAgo(30));
            git.branchCreate().setName("main").call();
            git.branchCreate().setName("preprod").call();
            git.checkout().setName("preprod").call();
            commit(git, "app.txt", "v2", "feature ready for production", daysAgo(10));
            git.checkout().setName("develop").call();
            git.merge().include(git.getRepository().resolve("preprod")).call();
        }

        AlertResult result = template().evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals(List.of("deployment_delayed"), result.statusMessage().get("warnings"));
        Map<String, Object> last = transitions(result).get(1);
        assertEquals("preprod", last.get("from"));
        assertEquals("main", last.get("to"));
        assertEquals(1, last.get("pendingCommits"));
        assertEquals(10L, last.get("oldestPendingAgeDays"));
        assertEquals(true, last.get("delayed"));
        assertEquals(false, merge(result).get("conflict"));
    }

    @Test
    void doesNotWarnForPendingCommitsYoungerThanTheThreshold() throws Exception {
        try (Git git = Git.init().setInitialBranch("develop").setDirectory(remote.toFile()).call()) {
            commit(git, "app.txt", "v1", "initial", daysAgo(30));
            git.branchCreate().setName("main").call();
            git.branchCreate().setName("preprod").call();
            git.checkout().setName("preprod").call();
            commit(git, "app.txt", "v2", "fresh feature", daysAgo(1));
            git.checkout().setName("develop").call();
            git.merge().include(git.getRepository().resolve("preprod")).call();
        }

        AlertResult result = template().evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        Map<String, Object> last = transitions(result).get(1);
        assertEquals(1, last.get("pendingCommits"));
        assertEquals(false, last.get("delayed"));
    }

    @Test
    void reportsOnlyTheLastTransitionAsDelayed() throws Exception {
        try (Git git = Git.init().setInitialBranch("develop").setDirectory(remote.toFile()).call()) {
            commit(git, "app.txt", "v1", "initial", daysAgo(30));
            git.branchCreate().setName("main").call();
            git.branchCreate().setName("preprod").call();
            commit(git, "app.txt", "v2", "only in develop for weeks", daysAgo(20));
        }

        AlertResult result = template().evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        List<Map<String, Object>> transitions = transitions(result);
        assertEquals(1, transitions.get(0).get("pendingCommits"));
        assertEquals(20L, transitions.get(0).get("oldestPendingAgeDays"));
        assertFalse(transitions.get(0).containsKey("delayed"));
        assertEquals(0, transitions.get(1).get("pendingCommits"));
    }

    @Test
    void warnsWhenPromotingThePreviousBranchWouldConflict() throws Exception {
        try (Git git = Git.init().setInitialBranch("develop").setDirectory(remote.toFile()).call()) {
            commit(git, "app.txt", "line\n", "initial", daysAgo(30));
            git.branchCreate().setName("main").call();
            git.branchCreate().setName("preprod").call();
            git.checkout().setName("preprod").call();
            commit(git, "app.txt", "preprod line\n", "preprod change", daysAgo(1));
            git.checkout().setName("develop").call();
            git.merge().include(git.getRepository().resolve("preprod")).call();
            git.checkout().setName("main").call();
            commit(git, "app.txt", "main line\n", "hotfix straight to main", daysAgo(1));
        }
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = template().evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals(List.of("merge_conflict", "commits_outside_flow"), result.statusMessage().get("warnings"));
        assertEquals(true, merge(result).get("conflict"));
        assertEquals(List.of("app.txt"), merge(result).get("conflictingPaths"));
        assertTrue(context.getState().contains("conflict=true"));
    }

    @Test
    void warnsWhenTheLastBranchHasCommitsMissingFromAnyEarlierBranch() throws Exception {
        try (Git git = Git.init().setInitialBranch("develop").setDirectory(remote.toFile()).call()) {
            commit(git, "app.txt", "v1", "initial", daysAgo(30));
            git.branchCreate().setName("preprod").call();
            git.branchCreate().setName("main").call();
            git.checkout().setName("main").call();
            commit(git, "hotfix.txt", "patch", "hotfix straight to main", daysAgo(2));
            git.checkout().setName("preprod").call();
            git.merge().include(git.getRepository().resolve("main")).call();
        }
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = template().evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals(List.of("commits_outside_flow"), result.statusMessage().get("warnings"));
        List<Map<String, Object>> outside = outsideFlow(result);
        assertEquals("develop", outside.get(0).get("missingFrom"));
        assertEquals(1, outside.get(0).get("count"));
        assertEquals("preprod", outside.get(1).get("missingFrom"));
        assertEquals(0, outside.get(1).get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commits = (List<Map<String, Object>>) outside.get(0).get("commits");
        assertEquals("hotfix straight to main", commits.get(0).get("message"));
        assertTrue(context.getState().contains("commitsOutsideFlow=1"));
    }

    @Test
    void warnsWhenAFlowBranchDoesNotExistInTheRemote() throws Exception {
        try (Git git = Git.init().setInitialBranch("develop").setDirectory(remote.toFile()).call()) {
            commit(git, "app.txt", "v1", "initial", daysAgo(1));
        }
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = template().evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals("branch_not_found", result.statusMessage().get("failureReason"));
        assertNotNull(result.statusMessage().get("failureMessage"));
        assertNoTokenLeak(result, context);
    }

    @Test
    void warnsWithoutExposingTheTokenWhenTheRemoteIsUnreachable() throws Exception {
        GitCredentials credentials = new GitCredentials(GitProvider.GITLAB, "127.0.0.1:1", null, TOKEN, null);
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = new GitBranchFlowAlertTemplate(credentials, "group/project", FLOW, 3, 5).evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertNotNull(result.statusMessage().get("failureReason"));
        assertEquals("group/project", result.statusMessage().get("repository"));
        assertNoTokenLeak(result, context);
    }

    @Test
    void removesTheDisposableWorkspaceAfterEvaluating() throws Exception {
        try (Git git = Git.init().setInitialBranch("develop").setDirectory(remote.toFile()).call()) {
            commit(git, "app.txt", "v1", "initial", daysAgo(1));
            git.branchCreate().setName("preprod").call();
            git.branchCreate().setName("main").call();
        }
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long before = workspaces(tmp);

        template().evaluate(new AlertExecutionContext());

        assertEquals(before, workspaces(tmp));
    }

    private GitBranchFlowAlertTemplate template() {
        return new GitBranchFlowAlertTemplate(credentials(GitProvider.GITHUB), remote.toUri().toString(), FLOW, 3, 60);
    }

    private static GitCredentials credentials(GitProvider provider) {
        return new GitCredentials(provider, "github.com", null, TOKEN, null);
    }

    private void commit(Git git, String file, String content, String message, Instant when) throws Exception {
        Files.writeString(remote.resolve(file), content);
        git.add().addFilepattern(file).call();
        PersonIdent ident = new PersonIdent("Tester", "tester@example.org", when, ZoneOffset.UTC);
        git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call();
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days)).minusSeconds(60);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> transitions(AlertResult result) {
        return (List<Map<String, Object>>) result.statusMessage().get("transitions");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(AlertResult result) {
        return (Map<String, Object>) result.statusMessage().get("merge");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> outsideFlow(AlertResult result) {
        return (List<Map<String, Object>>) result.statusMessage().get("commitsOutsideFlow");
    }

    private static void assertNoTokenLeak(AlertResult result, AlertExecutionContext context) {
        assertFalse(result.statusMessage().toString().contains(TOKEN));
        assertFalse(context.getState().contains(TOKEN));
    }

    private static long workspaces(Path tmp) throws Exception {
        try (var entries = Files.list(tmp)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("alertify-git-")).count();
        }
    }

    private static AlertParameter parameter(String fieldName) throws ReflectiveOperationException {
        return GitBranchFlowAlertTemplate.class.getDeclaredField(fieldName).getAnnotation(AlertParameter.class);
    }
}
