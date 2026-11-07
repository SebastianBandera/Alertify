package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import app.alertify.playwright.PlaywrightUsageAnalyzer.Edge;
import app.alertify.playwright.PlaywrightUsageAnalyzer.Key;
import app.alertify.playwright.PlaywrightUsageAnalyzer.Resource;
import app.alertify.playwright.PlaywrightUsageAnalyzer.Script;

class PlaywrightUsageAnalyzerTest {
    private final Key config = new Key("CONFIGURATION", 1);
    private final Key secret = new Key("SECRET", 2);
    private final Key credentials = new Key("SECRET", 3);
    private final Key procedure = new Key("PROCEDURE", 4);
    private final List<Resource> resources = List.of(new Resource(config, "Shared config"), new Resource(secret, "Token"),
            new Resource(credentials, "My credentials"), new Resource(procedure, "Obtain token"));

    @Test
    void discoversAllFillFormsCaseInsensitiveAndDeduplicatesCredentialPointers() {
        var analysis = analyze("""
                # FILL secrets.not-a-reference
                QUERY #input
                FILL configs.shared CONFIG
                FILL secrets.TOKEN
                FILL secrets.Token
                FILL SECRET "My credentials" POINTER "/username"
                FILL SECRET "My credentials" POINTER "/password"
                CHECK TEXT FILL secrets.not-a-reference
                """, List.of());
        assertThat(analysis.complete()).isTrue();
        for (Key target : List.of(config, secret, credentials)) {
            assertThat(analysis.consumers(target.type(), target.id())).singleElement().satisfies(consumer -> {
                assertThat(consumer.direct()).isTrue();
                assertThat(consumer.enabled()).isFalse();
            });
        }
    }

    @Test
    void followsExpressionsAndCallProcedureBindingsThroughCyclesWithoutExecutingAnything() {
        Key nested = new Key("PROCEDURE", 5);
        var analysis = analyze("CALL PROCEDURE \"Obtain token\" POINTER \"/value\" AS token", List.of(
                new Edge(procedure, nested), new Edge(nested, procedure), new Edge(nested, credentials),
                new Edge(credentials, secret), new Edge(secret, config)));
        for (Key target : List.of(config, secret, credentials))
            assertThat(analysis.consumers(target.type(), target.id())).singleElement().satisfies(consumer -> assertThat(consumer.direct()).isFalse());

        assertThat(analysis.complete()).isTrue();
    }

    @Test
    void directFillWinsOverAnExpressionOrProcedurePath() {
        var analysis = analyze("QUERY #input\nFILL configs.Shared config\nFILL secrets.Token\nCALL PROCEDURE \"Obtain token\" POINTER \"\" AS result",
                List.of(new Edge(secret, config), new Edge(procedure, config)));
        assertThat(analysis.consumers("CONFIGURATION", 1)).singleElement().satisfies(consumer -> assertThat(consumer.direct()).isTrue());
    }

    @Test
    void resolvesEachConfigurationScriptOnceAndReflectsItsCurrentContentsOnNextAnalysis() {
        var calls = new AtomicInteger();
        var scripts = List.of(new Script(10, "One", true, "CONFIGURATION", null, 1L), new Script(11, "Two", true, "CONFIGURATION", null, 1L));
        var analysis = PlaywrightUsageAnalyzer.analyze(scripts, resources, List.of(), _ -> {
            calls.incrementAndGet();
            return "QUERY #input\nFILL secrets.Token";
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(analysis.consumers("SECRET", 2)).hasSize(2);
        var updated = PlaywrightUsageAnalyzer.analyze(scripts, resources, List.of(), _ -> "QUERY #input\nFILL configs.Shared config");
        assertThat(updated.consumers("SECRET", 2)).isEmpty();
        assertThat(updated.consumers("CONFIGURATION", 1)).hasSize(2);
    }

    @Test
    void excludesSecretScriptsWithoutResolvingThemAndMarksEveryResultIncomplete() {
        var analysis = PlaywrightUsageAnalyzer.analyze(List.of(new Script(10, "Secret script", true, "SECRET", null, null)), resources, List.of(), _ -> {
            throw new AssertionError("Must not resolve a secret script");
        });
        assertThat(analysis.complete()).isFalse();
        assertThat(analysis.warnings()).containsExactly("SECRET_SCRIPT_EXCLUDED");
        assertThat(analysis.issues()).containsExactly(new PlaywrightUsageIssue("SECRET_SCRIPT_EXCLUDED", 10, "Secret script"));
        assertThat(analysis.consumers("SECRET", 2)).isEmpty();
    }

    @Test
    void isolatesInvalidUnavailableAndMissingReferencesWithoutExposingErrors() {
        var scripts = List.of(new Script(10, "Invalid", true, "TEXT", "SECRET CONTENT IS INVALID", null),
                new Script(11, "Unavailable", true, "CONFIGURATION", null, 1L),
                new Script(12, "Missing", true, "TEXT", "QUERY #input\nFILL secrets.Missing", null),
                new Script(13, "Valid", true, "TEXT", "QUERY #input\nFILL secrets.Token", null));
        var analysis = PlaywrightUsageAnalyzer.analyze(scripts, resources, List.of(), _ -> {
            throw new IllegalArgumentException("sensitive expression value");
        });
        assertThat(analysis.warnings()).containsExactly("INVALID_SCRIPT", "REFERENCE_UNAVAILABLE", "SCRIPT_UNAVAILABLE");
        assertThat(analysis.issues()).containsExactly(new PlaywrightUsageIssue("INVALID_SCRIPT", 10, "Invalid"),
                new PlaywrightUsageIssue("REFERENCE_UNAVAILABLE", 12, "Missing"), new PlaywrightUsageIssue("SCRIPT_UNAVAILABLE", 11, "Unavailable"));
        assertThat(analysis.consumers("SECRET", 2)).singleElement().satisfies(consumer -> assertThat(consumer.id()).isEqualTo(13));
    }

    @Test
    void associatesCachedConfigurationFailuresWithEveryAffectedAlertAndDeduplicatesReferences() {
        var calls = new AtomicInteger();
        var scripts = List.of(new Script(10, "One", true, "CONFIGURATION", null, 1L),
                new Script(11, "Two", false, "CONFIGURATION", null, 1L),
                new Script(12, "Missing references", false, "TEXT", "QUERY #input\nFILL secrets.Missing\nFILL secrets.Also missing", null));
        var analysis = PlaywrightUsageAnalyzer.analyze(scripts, resources, List.of(), _ -> {
            calls.incrementAndGet();
            return "UNSUPPORTED SENSITIVE TEXT";
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(analysis.issues()).containsExactly(new PlaywrightUsageIssue("INVALID_SCRIPT", 10, "One"),
                new PlaywrightUsageIssue("INVALID_SCRIPT", 11, "Two"), new PlaywrightUsageIssue("REFERENCE_UNAVAILABLE", 12, "Missing references"));
    }

    private PlaywrightUsageAnalyzer.Analysis analyze(String text, List<Edge> edges) {
        return PlaywrightUsageAnalyzer.analyze(List.of(new Script(10, "Saved alert", false, "TEXT", text, null)), resources, edges, _ -> {
            throw new AssertionError("Literal scripts need no value resolution");
        });
    }
}
