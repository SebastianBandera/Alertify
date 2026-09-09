package app.alertify.procedures.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.csv.CsvSupport;
import app.alertify.api.error.InvalidProcedureImportException;
import app.alertify.jpa.entity.Tag;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureParameterValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** CSV format for procedure definitions; secret values are never exported. */
@Component
class ProcedureCsvCodec {
    private static final List<String> HEADER = List.of(
            "name", "description", "templateKey", "enabled", "allowConcurrentExecutions", "parameters", "tags"
    );
    private static final int MAX_ROWS = 10_000;
    private static final Pattern TAG_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private final JsonMapper jsonMapper;

    ProcedureCsvCodec(JsonMapper jsonMapper) { this.jsonMapper = jsonMapper; }

    byte[] write(List<Procedure> procedures, Map<Long, List<ProcedureParameterValue>> valuesById) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        CsvSupport.appendRow(csv, HEADER);
        for (Procedure procedure : procedures) {
            List<ExportParameter> parameters = valuesById.getOrDefault(procedure.getId(), List.of()).stream()
                    .map(ProcedureCsvCodec::exportParameter).toList();
            List<ExportTag> tags = procedure.getTags().stream()
                    .sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(tag -> new ExportTag(tag.getName(), tag.getColor())).toList();
            CsvSupport.appendRow(csv, List.of(procedure.getName(), nullable(procedure.getDescription()),
                    procedure.getTemplate().getTemplateKey(), Boolean.toString(procedure.isEnabled()),
                    Boolean.toString(procedure.isConcurrentExecutionAllowed()), json(parameters), json(tags)));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ExportParameter exportParameter(ProcedureParameterValue value) {
        String exported = switch (value.getSource()) {
            case TEXT -> value.getTextValue();
            case CONFIGURATION -> value.getConfiguration().getName();
            case SECRET -> value.getSecret().getName();
            case PROCEDURE -> value.getReferencedProcedure().getName();
        };
        return new ExportParameter(value.getTemplateParameter().getParameterKey(), value.getSource(), exported);
    }

    List<ImportRow> read(byte[] content) {
        String csv = new String(content, StandardCharsets.UTF_8);
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);

        List<List<String>> rows;

        try {
            rows = CsvSupport.parseCsv(csv);
        } catch (IllegalArgumentException exception) {
            throw new InvalidProcedureImportException(exception.getMessage(), exception);
        }
        if (rows.isEmpty()) throw new InvalidProcedureImportException("The CSV file is empty");

        if (!rows.getFirst().equals(HEADER))
            throw new InvalidProcedureImportException("CSV header must be exactly: " + String.join(",", HEADER));

        if (rows.size() - 1 > MAX_ROWS)
            throw new InvalidProcedureImportException("CSV contains more than " + MAX_ROWS + " procedures");

        Map<String, Integer> names = new LinkedHashMap<>();
        List<ImportRow> result = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> fields = rows.get(index);
            int row = index + 1;
            if (fields.size() != HEADER.size()) throw error(row, "expected " + HEADER.size() + " columns");

            String name = fields.get(0).trim();

            if (name.isEmpty() || name.length() > 200) throw error(row, "name must contain between 1 and 200 characters");

            Integer previous = names.putIfAbsent(name.toLowerCase(Locale.ROOT), row);

            if (previous != null) throw error(row, "duplicate procedure name also present on row " + previous);

            String description = fields.get(1).trim().isEmpty() ? null : fields.get(1).trim();

            if (description != null && description.length() > 2000) throw error(row, "description exceeds 2000 characters");

            String templateKey = fields.get(2).trim();

            if (templateKey.isEmpty()) throw error(row, "templateKey is required");

            boolean enabled = parseBoolean(fields.get(3), row);
            boolean allowConcurrentExecutions = parseBoolean(fields.get(4), row, "allowConcurrentExecutions");

            result.add(new ImportRow(row, name, description, templateKey, enabled, allowConcurrentExecutions,
                    parameters(fields.get(5), row), tags(fields.get(6), row)));
        }
        return List.copyOf(result);
    }

    private List<ImportParameter> parameters(String raw, int row) {
        JsonNode node = tree(raw, "parameters", row);
        if (node == null) return List.of();

        if (!node.isArray()) throw error(row, "parameters must be a JSON array");

        Set<String> keys = new LinkedHashSet<>();

        List<ImportParameter> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isObject() || !string(value, "key") || !string(value, "source") || !string(value, "value"))
                throw error(row, "each parameter requires string key, source, and value properties");

            String key = value.get("key").stringValue().trim();
            if (key.isEmpty() || key.length() > 255) throw error(row, "parameter key must contain between 1 and 255 characters");

            if (!keys.add(key.toLowerCase(Locale.ROOT))) throw error(row, "parameter '" + key + "' is listed more than once");

            AlertParameterSource source;

            try {
                source = AlertParameterSource.valueOf(value.get("source").stringValue().trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw error(row, "parameter '" + key + "' has an invalid source", exception);
            }
            String parameterValue = value.get("value").stringValue();
            if (source != AlertParameterSource.TEXT && parameterValue.isBlank())
                throw error(row, "parameter '" + key + "' requires the referenced name");

            result.add(new ImportParameter(key, source,
                    source == AlertParameterSource.TEXT ? parameterValue : parameterValue.trim()));
        }
        return List.copyOf(result);
    }

    private List<ImportTag> tags(String raw, int row) {
        JsonNode node = tree(raw, "tags", row);
        if (node == null) return List.of();

        if (!node.isArray()) throw error(row, "tags must be a JSON array");

        Map<String, ImportTag> result = new LinkedHashMap<>();

        for (JsonNode value : node) {
            if (!value.isObject() || !string(value, "name") || !string(value, "color"))
                throw error(row, "each tag requires string name and color properties");

            String name = value.get("name").stringValue().trim();
            String color = value.get("color").stringValue().trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty() || name.length() > 100) throw error(row, "tag name must contain between 1 and 100 characters");

            if (!TAG_COLOR.matcher(color).matches()) throw error(row, "tag color must use #RRGGBB format");

            result.putIfAbsent(name.toLowerCase(Locale.ROOT), new ImportTag(name, color));
        }
        return List.copyOf(result.values());
    }

    private JsonNode tree(String raw, String field, int row) {
        if (raw.isBlank()) return null;

        try { return jsonMapper.readTree(raw); }
        catch (Exception exception) { throw error(row, field + " is not valid JSON", exception); }
    }

    private static boolean string(JsonNode node, String field) {
        return node.get(field) != null && node.get(field).isString();
    }

    private static boolean parseBoolean(String value, int row) {
        return parseBoolean(value, row, "enabled");
    }

    private static boolean parseBoolean(String value, int row, String field) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw error(row, field + " must be true or false");
        };
    }

    private String json(Object value) {
        try { return jsonMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize procedure CSV", exception); }
    }

    private static String nullable(String value) { return value == null ? "" : value; }
    private static InvalidProcedureImportException error(int row, String message) {
        return new InvalidProcedureImportException("CSV row " + row + ": " + message);
    }
    private static InvalidProcedureImportException error(int row, String message, Exception cause) {
        return new InvalidProcedureImportException("CSV row " + row + ": " + message, cause);
    }

    record ImportRow(int rowNumber, String name, String description, String templateKey, boolean enabled, boolean allowConcurrentExecutions, List<ImportParameter> parameters, List<ImportTag> tags) { }
    record ImportParameter(String key, AlertParameterSource source, String value) { }
    record ImportTag(String name, String color) { }
    private record ExportParameter(String key, AlertParameterSource source, String value) { }
    private record ExportTag(String name, String color) { }
}
