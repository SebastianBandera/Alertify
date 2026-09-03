package app.alertify.api.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class CsvSupportTest {

    @Test
    void writesUnquotedFieldsSeparatedByCommasAndTerminatedByCrlf() {
        StringBuilder csv = new StringBuilder();

        CsvSupport.appendRow(csv, List.of("name", "value", "enabled"));

        assertThat(csv.toString()).isEqualTo("name,value,enabled\r\n");
    }

    @Test
    void quotesOnlyFieldsContainingCommaQuoteOrLineBreak() {
        StringBuilder csv = new StringBuilder();

        CsvSupport.appendRow(csv, List.of("plain", "a,b", "say \"hi\"", "line1\nline2", "carriage\rreturn"));

        assertThat(csv.toString())
                .isEqualTo("plain,\"a,b\",\"say \"\"hi\"\"\",\"line1\nline2\",\"carriage\rreturn\"\r\n");
    }

    @Test
    void roundTripsFieldsThatRequireQuoting() {
        List<String> fields = List.of("plain", "a,b", "say \"hi\"", "line1\nline2", "");
        StringBuilder csv = new StringBuilder();
        CsvSupport.appendRow(csv, fields);

        List<List<String>> rows = CsvSupport.parseCsv(csv.toString());

        assertThat(rows).containsExactly(fields);
    }

    @Test
    void parsesLfCrlfAndCrTerminators() {
        assertThat(CsvSupport.parseCsv("a,b\nc,d")).containsExactly(List.of("a", "b"), List.of("c", "d"));
        assertThat(CsvSupport.parseCsv("a,b\r\nc,d")).containsExactly(List.of("a", "b"), List.of("c", "d"));
        assertThat(CsvSupport.parseCsv("a,b\rc,d")).containsExactly(List.of("a", "b"), List.of("c", "d"));
    }

    @Test
    void skipsFullyBlankRows() {
        List<List<String>> rows = CsvSupport.parseCsv("a,b\r\n\r\n,\r\nc,d\r\n");

        assertThat(rows).containsExactly(List.of("a", "b"), List.of("c", "d"));
    }

    @Test
    void keepsTrailingRowWithoutTerminator() {
        assertThat(CsvSupport.parseCsv("a,b")).containsExactly(List.of("a", "b"));
    }

    @Test
    void rejectsUnterminatedQuote() {
        assertThatThrownBy(() -> CsvSupport.parseCsv("\"unterminated"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Malformed CSV quoting");
    }

    @Test
    void rejectsQuoteStartingMidField() {
        assertThatThrownBy(() -> CsvSupport.parseCsv("ab\"cd\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Malformed CSV quoting");
    }

    @Test
    void returnsNoRowsForEmptyInput() {
        assertThat(CsvSupport.parseCsv("")).isEmpty();
    }
}
