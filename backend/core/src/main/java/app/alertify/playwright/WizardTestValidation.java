package app.alertify.playwright;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Server-owned evidence from clean tests, independent of the interactive browser. */
final class WizardTestValidation {
    static final List<String> ENGINES = List.of("chromium", "firefox", "webkit");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WizardTestValidation() { }

    static ObjectNode current(JsonNode document, String revision, String legacyRevision, List<String> required) {
        JsonNode saved = document.path("testValidation");
        if (revision.equals(saved.path("revision").asString()) && saved.path("results").isObject())
            return (ObjectNode) saved.deepCopy();

        ObjectNode validation = JSON.createObjectNode().put("revision", revision);
        validation.set("results", JSON.createObjectNode());
        // Failed legacy reports can outlive edits. Only migrate a complete success
        // whose existing server-issued revision still matches the current draft.
        if (!document.has("testValidation") && legacyRevision.equals(document.path("testedRevision").asString()) && document.path("testReport").path("passed").asBoolean()) {
            try {
                validateReport(document.path("testReport"), required);
                record(validation, document.path("testReport"));
            } catch (IllegalArgumentException ignored) { /* Unverifiable history is not evidence. */ }
        }
        return validation;
    }

    static void invalidate(ObjectNode validation, List<String> engines) {
        ObjectNode results = (ObjectNode) validation.path("results");
        engines.forEach(results::remove);
    }

    static void validateReport(JsonNode report, List<String> expected) {
        if (!report.path("browsers").isArray() || !report.path("passed").isBoolean())
            throw new IllegalArgumentException("The test returned an invalid browser report");

        Set<String> seen = new HashSet<>();
        boolean passed = true;
        for (JsonNode result : report.path("browsers")) {
            String engine = result.path("browser").asString();
            if (!expected.contains(engine) || !seen.add(engine) || !result.path("passed").isBoolean() || !result.path("completed").isIntegralNumber() || result.path("completed").asInt(-1) < 0)
                throw new IllegalArgumentException("The test returned an unexpected browser result");

            passed &= result.path("passed").asBoolean();
        }
        if (!seen.equals(new HashSet<>(expected)) || report.path("passed").asBoolean() != passed)
            throw new IllegalArgumentException("The test did not report the requested browsers consistently");
    }

    static void record(ObjectNode validation, JsonNode report) {
        ObjectNode results = (ObjectNode) validation.path("results");
        for (JsonNode result : report.path("browsers")) {
            ObjectNode entry = JSON.createObjectNode().put("passed", result.path("passed").asBoolean()).put("completed", result.path("completed").asInt()).put("testedAt", Instant.now().toString());
            if (!result.path("error").asString().isBlank()) entry.put("error", result.path("error").asString());

            results.set(result.path("browser").asString(), entry);
        }
    }

    static void fail(ObjectNode validation, List<String> engines) {
        ObjectNode results = (ObjectNode) validation.path("results");
        for (String engine : engines)
            results.set(engine, JSON.createObjectNode().put("passed", false).put("completed", 0).put("testedAt", Instant.now().toString()).put("error", "The clean test was interrupted or did not return a valid result."));
    }

    static boolean passed(JsonNode validation, List<String> required) {
        return !required.isEmpty() && required.stream().allMatch(engine -> validation.path("results").path(engine).path("passed").asBoolean());
    }

    static void preserveMetadata(JsonNode original, ObjectNode safe) {
        JsonNode source = original.path("testValidation");
        if (!source.isObject()) return;

        ObjectNode validation = (ObjectNode) safe.path("testValidation");
        validation.put("revision", source.path("revision").asString());
        for (String engine : ENGINES) {
            JsonNode entry = source.path("results").path(engine);
            if (!entry.isObject()) continue;

            ObjectNode target = (ObjectNode) validation.path("results").path(engine);
            target.put("passed", entry.path("passed").asBoolean()).put("completed", entry.path("completed").asInt()).put("testedAt", entry.path("testedAt").asString());
        }
    }

    static void preserveReportMetadata(JsonNode original, JsonNode safe) {
        if (!original.isObject() || !safe.isObject()) return;

        ObjectNode target = (ObjectNode) safe;
        if (original.path("requestedBrowsers").isArray() && original.path("requestedBrowsers").valueStream().allMatch(value -> ENGINES.contains(value.asString())))
            target.set("requestedBrowsers", original.path("requestedBrowsers").deepCopy());

        String active = original.path("activeEngine").asString();
        if (original.has("activeEngine") && (active.isEmpty() || ENGINES.contains(active))) target.put("activeEngine", active);

        for (int index = 0; index < original.path("browsers").size(); index++) {
            String engine = original.path("browsers").get(index).path("browser").asString();
            if (ENGINES.contains(engine) && target.path("browsers").path(index).isObject())
                ((ObjectNode) target.path("browsers").path(index)).put("browser", engine);
        }
    }
}
