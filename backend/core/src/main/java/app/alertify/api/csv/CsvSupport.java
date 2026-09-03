package app.alertify.api.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 primitives shared by the CSV import/export codecs. Writing always
 * uses CRLF terminators and quotes only the fields that require it; parsing
 * accepts LF, CRLF or CR terminators and skips fully blank rows.
 */
public final class CsvSupport {

    private CsvSupport() {
    }

    public static void appendRow(StringBuilder csv, List<String> fields) {
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0)
                csv.append(',');

            appendField(csv, fields.get(index));
        }
        csv.append("\r\n");
    }

    public static void appendField(StringBuilder csv, String value) {
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
        if (!quote) {
            csv.append(value);
            return;
        }
        csv.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"')
                csv.append('"');

            csv.append(character);
        }
        csv.append('"');
    }

    /**
     * Splits the CSV text into rows of raw fields.
     *
     * @throws IllegalArgumentException when quoting is malformed; callers wrap
     *         it in their own domain exception.
     */
    public static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;

        for (int index = 0; index < csv.length(); index++) {
            char character = csv.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(character);
                }
                continue;
            }

            if (character == '"') {
                if (fieldStarted || field.length() > 0) {
                    throw new IllegalArgumentException("Malformed CSV quoting");
                }
                quoted = true;
                fieldStarted = true;
            } else if (character == ',') {
                row.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (character == '\r' || character == '\n') {
                row.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
                if (!row.stream().allMatch(String::isBlank))
                    rows.add(List.copyOf(row));

                row.clear();
                if (character == '\r' && index + 1 < csv.length() && csv.charAt(index + 1) == '\n') {
                    index++;
                }
            } else {
                field.append(character);
                fieldStarted = true;
            }
        }

        if (quoted)
            throw new IllegalArgumentException("Malformed CSV quoting");

        if (fieldStarted || field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            if (!row.stream().allMatch(String::isBlank))
                rows.add(List.copyOf(row));
        }
        return rows;
    }
}
