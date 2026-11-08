package app.alertify.playwright;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** Session-only execution evidence. Snapshots contain line numbers, never DSL arguments or resolved values. */
final class WizardScriptProgress {
    private String source = "";
    private List<Integer> commandLines = List.of();
    private String engine = "";
    private String status = "UNVERIFIED";
    private int completed;
    private Integer activeLine;
    private Integer failedLine;

    synchronized void reset(String script, String browser) {
        source = normalize(script);
        engine = browser;
        completed = 0;
        activeLine = null;
        failedLine = null;
        try {
            commandLines = lines(source);
            status = "READY";
        } catch (IllegalArgumentException invalid) {
            commandLines = List.of();
            status = "UNVERIFIED";
        }
    }

    synchronized void begin(String script) {
        source = normalize(script);
        status = "UNVERIFIED";
        activeLine = null;
        failedLine = null;
    }

    synchronized void accept(JsonNode event) {
        String phase = event.path("phase").asString();
        String browser = event.path("engine").asString();
        if (!List.of("chromium", "firefox", "webkit").contains(browser))
            return;

        engine = browser;
        if (phase.equals("OPENING") || phase.equals("UNAVAILABLE")) {
            status = "UNVERIFIED";
            completed = 0;
            activeLine = null;
            failedLine = null;
            return;
        }
        if (phase.equals("STARTED")) {
            commandLines = event.path("commandLines").valueStream().map(JsonNode::asInt).toList();
            if (commandLines.size() > 500 || commandLines.stream().anyMatch(line -> line < 1)
                    || !commandLines.equals(commandLines.stream().distinct().sorted().toList())) {
                status = "UNVERIFIED";
                return;
            }
            activeLine = null;
            failedLine = null;
        } else if (status.equals("UNVERIFIED"))
            return;

        int count = event.path("completed").asInt(-1);
        if (count < 0 || count > commandLines.size()) {
            status = "UNVERIFIED";
            return;
        }
        completed = count;
        int line = event.path("line").asInt();
        switch (phase) {
            case "STARTED", "COMPLETED" -> {
                status = "READY";
                activeLine = null;
                failedLine = null;
            }
            case "RUNNING", "FAILED" -> {
                if (count >= commandLines.size() || line != commandLines.get(count)) {
                    status = "UNVERIFIED";
                    return;
                }
                status = phase;
                activeLine = phase.equals("RUNNING") ? line : null;
                failedLine = phase.equals("FAILED") ? line : null;
            }
            default -> status = "UNVERIFIED";
        }
    }

    synchronized void interrupted() {
        if (activeLine != null) {
            status = "UNVERIFIED";
            activeLine = null;
        }
    }

    synchronized Snapshot snapshot(String script, boolean linked, boolean sameContext) {
        String current = normalize(script);
        if (!sameContext)
            return unknown(current);

        List<Integer> currentLines = commandLines;
        if (!current.equals(source)) {
            try {
                currentLines = lines(current);
                if (linked || completed > currentLines.size() || !prefix(source, commandLines, completed).equals(prefix(current, currentLines, completed))
                        || ((activeLine != null || failedLine != null) && !current.startsWith(source + "\n")))
                    return unknown(current);

            } catch (IllegalArgumentException invalid) {
                return unknown(current);
            }
        }
        if (status.equals("UNVERIFIED"))
            return unknown(current);

        return new Snapshot(revision(current), engine, status, currentLines, completed, activeLine, failedLine);
    }

    static Snapshot inactive(String script, boolean linked) {
        try {
            return new Snapshot(revision(normalize(script)), "", "NO_SESSION", linked ? List.of() : lines(script), 0, null, null);
        } catch (IllegalArgumentException invalid) {
            return new Snapshot(revision(normalize(script)), "", "UNVERIFIED", List.of(), 0, null, null);
        }
    }

    private Snapshot unknown(String script) {
        return new Snapshot(revision(script), engine, "UNVERIFIED", List.of(), 0, null, null);
    }

    private static List<Integer> lines(String script) {
        return PlaywrightPageEngine.parseCommands(script).stream().map(PlaywrightPageEngine.Command::lineNumber).toList();
    }

    private static String prefix(String script, List<Integer> lines, int count) {
        return count == 0 ? "" : String.join("\n", Arrays.copyOfRange(normalize(script).split("\n", -1), 0, lines.get(count - 1)));
    }

    private static String normalize(String script) {
        return script.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String revision(String script) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("Cannot identify script revision"); }
    }

    record Snapshot(String revision, String engine, String status, List<Integer> commandLines, int completed, Integer activeLine, Integer failedLine) {
    }
}
