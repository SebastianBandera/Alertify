package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WizardScriptEditsTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void editsReferToOriginalPhysicalLinesAndPreserveCommentsBlanksAndTrailingLines() {
        var edits = json.readTree("""
                [{"type":"REPLACE","fromLine":2,"toLine":2,"text":""},
                 {"type":"INSERT","fromLine":4,"toLine":null,"text":"# New block\\nQUERY h2"},
                 {"type":"DELETE","fromLine":5,"toLine":5,"text":null}]
                """);
        var result = WizardScriptEdits.apply("# Original\r\nQUERY h1\r\n\r\nCHECK TEXT Old\r\n# remove\r\n", edits);
        assertThat(result.script()).isEqualTo("# Original\n\n\n# New block\nQUERY h2\nCHECK TEXT Old\n");
        assertThat(result.changes()).hasSize(3);
        assertThat(result.changes().getFirst().before()).isEqualTo("QUERY h1");
        assertThat(result.changes().getFirst().after()).isEmpty();
    }

    @Test
    void rejectsOverlapAndInsertionInsideAnEditedRangeButAllowsTheFollowingBoundary() {
        String replacement = "{\"type\":\"REPLACE\",\"fromLine\":1,\"toLine\":2,\"text\":\"QUERY main\"}";
        assertThatThrownBy(() -> WizardScriptEdits.apply("a\nb\nc", json.readTree("[" + replacement + ",{\"type\":\"INSERT\",\"fromLine\":2,\"toLine\":null,\"text\":\"# note\"}]"))).hasMessageContaining("overlap");
        assertThat(WizardScriptEdits.apply("a\nb\nc", json.readTree("[" + replacement + ",{\"type\":\"INSERT\",\"fromLine\":3,\"toLine\":null,\"text\":\"# note\"}]")).script()).isEqualTo("QUERY main\n# note\nc");
    }

    @Test
    void rejectsOutOfRangeAndOverflowPositionsWithoutProducingACandidate() {
        for (String position : new String[] { "0", "3", "4294967297", "999999999999999999999999999999" }) {
            assertThatThrownBy(() -> WizardScriptEdits.apply("one", json.readTree("[{\"type\":\"INSERT\",\"fromLine\":" + position + ",\"toLine\":null,\"text\":\"# note\"}]"))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> WizardScriptEdits.apply("one", json.readTree("[{\"type\":\"DELETE\",\"fromLine\":1,\"toLine\":4294967297,\"text\":null}]"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesTheEmptyDocumentsPhysicalLineAndDistinguishesDeleteFromBlankReplacement() {
        assertThat(WizardScriptEdits.apply("", json.readTree("[{\"type\":\"INSERT\",\"fromLine\":2,\"toLine\":null,\"text\":\"# appended\"}]")).script()).isEqualTo("\n# appended");
        assertThat(WizardScriptEdits.apply("one\ntwo", json.readTree("[{\"type\":\"REPLACE\",\"fromLine\":1,\"toLine\":1,\"text\":\"\"}]")).script()).isEqualTo("\ntwo");
        assertThat(WizardScriptEdits.apply("one\ntwo", json.readTree("[{\"type\":\"DELETE\",\"fromLine\":1,\"toLine\":1,\"text\":null}]")).script()).isEqualTo("two");
    }

    @Test
    void usesTheSameUnicodePhysicalSeparatorsAsTheParser() {
        String source = "# block\u2028QUERY h1\u0085CHECK TEXT Ready\u2029";
        assertThat(WizardScriptEdits.normalize(source)).isEqualTo("# block\nQUERY h1\nCHECK TEXT Ready\n");
        assertThat(WizardScriptEdits.apply(source, json.readTree("[{\"type\":\"REPLACE\",\"fromLine\":3,\"toLine\":3,\"text\":\"CHECK TEXT Updated\"}]")).script()).isEqualTo("# block\nQUERY h1\nCHECK TEXT Updated\n");
    }
}
