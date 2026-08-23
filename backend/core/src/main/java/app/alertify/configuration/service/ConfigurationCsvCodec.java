package app.alertify.configuration.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import app.alertify.api.error.InvalidConfigurationImportException;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class ConfigurationCsvCodec {

    private static final List<String> HEADER = List.of(
            "name", "description", "valueType", "value", "tags"
    );
    private static final int MAX_ROWS = 10_000;
    private static final Pattern TAG_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final JsonMapper jsonMapper;

    ConfigurationCsvCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    byte[] write(List<ApplicationConfiguration> configurations) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendRow(csv, HEADER);

        for (ApplicationConfiguration configuration : configurations) {
            if (SystemConfigurationPolicy.isValueHidden(configuration.getName()))
                continue;

            List<ExportTag> tags = configuration.getTags().stream()
                    .sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(tag -> new ExportTag(tag.getName(), tag.getColor()))
                    .toList();

            appendRow(
                    csv, List.of(
                            configuration.getName(),
                            configuration.getDescription() == null ? "" : configuration.getDescription(),
                            configuration.getValueType().name(),
                            exportValue(configuration),
                            writeJson(tags)
                    )
            );
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    List<ImportRow> read(byte[] content) {
        String csv = new String(content, StandardCharsets.UTF_8);
        if (csv.startsWith("\uFEFF"))
            csv = csv.substring(1);

        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) {
            throw new InvalidConfigurationImportException("The CSV file is empty");
        }
        if (!rows.getFirst().equals(HEADER)) {
            throw new InvalidConfigurationImportException("CSV header must be exactly: " + String.join(",", HEADER));
        }
        if (rows.size() - 1 > MAX_ROWS) {
            throw new InvalidConfigurationImportException("CSV contains more than " + MAX_ROWS + " configurations");
        }

        Map<String, Integer> names = new LinkedHashMap<>();
        List<ImportRow> result = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> fields = rows.get(index);
            int rowNumber = index + 1;
            if (fields.size() != HEADER.size()) {
                throw rowError(rowNumber, "expected " + HEADER.size() + " columns");
            }

            String name = fields.get(0).trim();
            if (name.isEmpty())
                throw rowError(rowNumber, "name is required");
            if (name.length() > 200)
                throw rowError(rowNumber, "name exceeds 200 characters");
            String normalizedName = name.toLowerCase(Locale.ROOT);
            Integer previousRow = names.putIfAbsent(normalizedName, rowNumber);
            if (previousRow != null) {
                throw rowError(rowNumber, "duplicate configuration name also present on row " + previousRow);
            }

            String description = fields.get(1).trim().isEmpty() ? null : fields.get(1).trim();
            if (description != null && description.length() > 2000) {
                throw rowError(rowNumber, "description exceeds 2000 characters");
            }

            ConfigurationValueType valueType;
            try {
                valueType = ConfigurationValueType.valueOf(fields.get(2).trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw rowError(rowNumber, "invalid valueType");
            }

            JsonNode value = parseValue(fields.get(3), valueType, rowNumber);

            List<ImportTag> tags = parseTags(fields.get(4), rowNumber);
            result.add(new ImportRow(rowNumber, name, description, valueType, value, tags));
        }
        return result;
    }

    private JsonNode parseValue(String rawValue, ConfigurationValueType valueType, int rowNumber) {
        if (valueType == ConfigurationValueType.STRING
                || valueType == ConfigurationValueType.EXPRESSION
                || valueType == ConfigurationValueType.DATE
                || valueType == ConfigurationValueType.DATE_TIME) {
            return StringNode.valueOf(rawValue);
        }
        try {
            return jsonMapper.readTree(rawValue);
        } catch (Exception exception) {
            throw rowError(rowNumber, "value is not valid for " + valueType, exception);
        }
    }

    private String exportValue(ApplicationConfiguration configuration) {
        return switch (configuration.getValueType()) {
            case STRING, EXPRESSION, DATE, DATE_TIME -> configuration.getValue().stringValue();
            default -> writeJson(configuration.getValue());
        };
    }

    private List<ImportTag> parseTags(String rawTags, int rowNumber) {
        if (rawTags.isBlank())
            return List.of();

        JsonNode tagsNode;
        try {
            tagsNode = jsonMapper.readTree(rawTags);
        } catch (Exception exception) {
            throw rowError(rowNumber, "tags is not valid JSON", exception);
        }
        if (tagsNode == null || !tagsNode.isArray()) {
            throw rowError(rowNumber, "tags must be a JSON array");
        }

        Map<String, ImportTag> tags = new LinkedHashMap<>();
        for (JsonNode tagNode : tagsNode) {
            if (!tagNode.isObject())
                throw rowError(rowNumber, "each tag must be a JSON object");
            JsonNode nameNode = tagNode.get("name");
            JsonNode colorNode = tagNode.get("color");
            if (nameNode == null || !nameNode.isString()) {
                throw rowError(rowNumber, "each tag requires a string name");
            }
            if (colorNode == null || !colorNode.isString()) {
                throw rowError(rowNumber, "each tag requires a string color");
            }

            String name = nameNode.stringValue().trim();
            String color = colorNode.stringValue().trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty() || name.length() > 100) {
                throw rowError(rowNumber, "tag name must contain between 1 and 100 characters");
            }
            if (!TAG_COLOR.matcher(color).matches()) {
                throw rowError(rowNumber, "tag color must use #RRGGBB format");
            }
            tags.putIfAbsent(name.toLowerCase(Locale.ROOT), new ImportTag(name, color));
        }
        return List.copyOf(tags.values());
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize configuration CSV", exception);
        }
    }

    private static void appendRow(StringBuilder csv, List<String> fields) {
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0)
                csv.append(',');
            appendField(csv, fields.get(index));
        }
        csv.append("\r\n");
    }

    private static void appendField(StringBuilder csv, String value) {
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

    private static List<List<String>> parseCsv(String csv) {
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
                    throw new InvalidConfigurationImportException("Malformed CSV quoting");
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
            throw new InvalidConfigurationImportException("Malformed CSV quoting");

        if (fieldStarted || field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            if (!row.stream().allMatch(String::isBlank))
                rows.add(List.copyOf(row));
        }
        return rows;
    }

    private static InvalidConfigurationImportException rowError(int row, String message) {
        return new InvalidConfigurationImportException("CSV row " + row + ": " + message);
    }

    private static InvalidConfigurationImportException rowError(int row, String message, Exception cause) {
        return new InvalidConfigurationImportException("CSV row " + row + ": " + message, cause);
    }

    record ImportRow(
        int rowNumber,
        String name,
        String description,
        ConfigurationValueType valueType,
        JsonNode value,
        List<ImportTag> tags
    ) {
    }

    record ImportTag(
        String name,
        String color
    ) {
    }

    private record ExportTag(
        String name,
        String color
    ) {
    }
}
