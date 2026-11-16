package app.alertify.playwright;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** Atomic physical-line edits. Every position belongs to the same original script. */
final class WizardScriptEdits {
    private WizardScriptEdits() { }

    static String normalize(String script) { return script.replaceAll("\\R", "\n"); }

    static Result apply(String original, JsonNode input) {
        String source = normalize(original);
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        if (!input.isArray() || input.isEmpty() || input.size() > 500)
            throw new IllegalArgumentException("Supply a nonempty batch of up to 500 line edits");

        List<Edit> edits = new ArrayList<>();
        for (JsonNode value : input) {
            String type = value.path("type").asString();
            if (!List.of("INSERT", "REPLACE", "DELETE").contains(type) || !value.path("fromLine").isIntegralNumber() || !value.path("fromLine").canConvertToInt())
                throw new IllegalArgumentException("Each edit requires a valid type and physical fromLine");

            int from = value.path("fromLine").asInt(-1);
            Integer to = value.path("toLine").isNull() ? null : value.path("toLine").isIntegralNumber() && value.path("toLine").canConvertToInt() ? value.path("toLine").asInt(-1) : -1;
            String text = value.path("text").isNull() ? null : value.path("text").isString() ? normalize(value.path("text").asString()) : null;
            if (from < 1 || from > lines.size() + (type.equals("INSERT") ? 1 : 0))
                throw new IllegalArgumentException("The line edit is outside the original script");

            if (type.equals("INSERT")) {
                if (to != null || text == null || text.isEmpty())
                    throw new IllegalArgumentException("INSERT requires toLine=null and nonempty text");
            } else {
                if (to == null || to < from || to > lines.size())
                    throw new IllegalArgumentException("The replacement/deletion interval is outside the original script");

                if (type.equals("DELETE") ? !value.path("text").isNull() : text == null)
                    throw new IllegalArgumentException("DELETE requires text=null; REPLACE requires a text string");
            }
            edits.add(new Edit(type, from, to, text));
        }
        edits.sort(Comparator.comparingInt(Edit::fromLine));
        for (int index = 1; index < edits.size(); index++) {
            Edit previous = edits.get(index - 1), current = edits.get(index);
            int end = previous.toLine() == null ? previous.fromLine() : previous.toLine();
            if (current.fromLine() <= end)
                throw new IllegalArgumentException("Line edits overlap or share an insertion anchor");
        }
        List<Change> changes = new ArrayList<>();
        for (Edit edit : edits) {
            String before = edit.toLine() == null ? "" : String.join("\n", lines.subList(edit.fromLine() - 1, edit.toLine()));
            changes.add(new Change(edit.type(), edit.fromLine(), edit.toLine(), before, edit.text() == null ? "" : edit.text()));
        }
        for (int index = edits.size() - 1; index >= 0; index--) {
            Edit edit = edits.get(index);
            if (edit.toLine() != null) lines.subList(edit.fromLine() - 1, edit.toLine()).clear();
            if (!edit.type().equals("DELETE")) lines.addAll(edit.fromLine() - 1, Arrays.asList(edit.text().split("\n", -1)));
        }
        String result = String.join("\n", lines);
        if (result.length() > 100_000)
            throw new IllegalArgumentException("The script exceeds 100000 characters");

        return new Result(result, List.copyOf(changes));
    }

    private record Edit(String type, int fromLine, Integer toLine, String text) { }
    record Change(String type, int fromLine, Integer toLine, String before, String after) { }
    record Result(String script, List<Change> changes) { }
}
