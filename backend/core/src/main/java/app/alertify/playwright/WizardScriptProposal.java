package app.alertify.playwright;

import java.net.URI;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** Small internal action contract shared by direct execution and approved batches. */
final class WizardScriptProposal {
    private WizardScriptProposal() { }

    static Action action(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return new Action("NONE", null, null);

        String type = node.path("type").asString();
        if (!List.of("NONE", "RESTART", "REPLAY").contains(type))
            throw new IllegalArgumentException("Invalid action after the script edit");

        Integer until = null;
        if (node.hasNonNull("untilLine")) {
            if (!node.path("untilLine").isIntegralNumber() || !node.path("untilLine").canConvertToInt()) throw new IllegalArgumentException("untilLine must be a physical line number");

            until = node.path("untilLine").asInt(-1);
        }
        List<String> engines = null;
        if (node.hasNonNull("engines")) {
            if (!node.path("engines").isArray()) throw new IllegalArgumentException("engines must be an array");

            engines = node.path("engines").valueStream().map(value -> value.asString()).toList();
            if (engines.isEmpty() || engines.size() > 3 || engines.stream().distinct().count() != engines.size() || !WizardTestValidation.ENGINES.containsAll(engines))
                throw new IllegalArgumentException("Invalid replay browser engines");
        }
        if (!type.equals("REPLAY") && (until != null || engines != null))
            throw new IllegalArgumentException("Only REPLAY accepts untilLine or engines");

        if (!type.equals("RESTART") && node.hasNonNull("url"))
            throw new IllegalArgumentException("Only RESTART accepts a replacement URL");

        return new Action(type, until, engines);
    }

    static String url(JsonNode node) {
        if (!node.isString()) throw new IllegalArgumentException("A replacement URL must be a string");

        String value = PlaywrightWizardService.normalizeUrl(node.asString());
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("An HTTP or HTTPS URL without embedded credentials is required"); }
        if (value.length() > 4000 || uri.getHost() == null || uri.getUserInfo() != null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("An HTTP or HTTPS URL without embedded credentials is required");

        return value;
    }

    record Action(String type, Integer untilLine, List<String> engines) { }
}
