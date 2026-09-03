package app.alertify.alerts.service;

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

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertParameterValue;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.csv.CsvSupport;
import app.alertify.api.error.InvalidAlertImportException;
import app.alertify.jpa.entity.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Encodes and validates the alert CSV import/export format. Parameters bound to
 * a secret export the secret name only; a secret value never reaches the CSV.
 */
@Component
class AlertCsvCodec {

    private static final List<String> HEADER = List.of(
            "name", "description", "templateKey", "cronExpression",
            "enabled", "allowConcurrentExecutions", "parameters", "tags"
    );
    private static final int MAX_ROWS = 10_000;
    private static final Pattern TAG_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final JsonMapper jsonMapper;

    AlertCsvCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    byte[] write(List<Alert> alerts, Map<Long, List<AlertParameterValue>> valuesByAlertId) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        CsvSupport.appendRow(csv, HEADER);

        for (Alert alert : alerts) {
            List<ExportTag> tags = alert.getTags().stream()
                    .sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(tag -> new ExportTag(tag.getName(), tag.getColor()))
                    .toList();

            List<ExportParameter> parameters = valuesByAlertId
                    .getOrDefault(alert.getId(), List.of())
                    .stream()
                    .map(AlertCsvCodec::exportParameter)
                    .toList();

            CsvSupport.appendRow(
                    csv, List.of(
                            alert.getName(),
                            alert.getDescription() == null ? "" : alert.getDescription(),
                            alert.getTemplate().getTemplateKey(),
                            alert.getCronExpression(),
                            Boolean.toString(alert.isEnabled()),
                            Boolean.toString(alert.isConcurrentExecutionAllowed()),
                            writeJson(parameters),
                            writeJson(tags)
                    )
            );
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ExportParameter exportParameter(AlertParameterValue value) {
        String exported = switch (value.getSource()) {
            case TEXT -> value.getTextValue();
            case CONFIGURATION -> value.getConfiguration().getName();
            case SECRET -> value.getSecret().getName();
        };
        return new ExportParameter(
                value.getTemplateParameter().getParameterKey(), value.getSource(),
                exported == null ? "" : exported
        );
    }

    List<ImportRow> read(byte[] content) {
        String csv = new String(content, StandardCharsets.UTF_8);
        if (csv.startsWith("\uFEFF"))
            csv = csv.substring(1);

        List<List<String>> rows = parseRows(csv);
        if (rows.isEmpty()) {
            throw new InvalidAlertImportException("The CSV file is empty");
        }
        if (!rows.getFirst().equals(HEADER)) {
            throw new InvalidAlertImportException("CSV header must be exactly: " + String.join(",", HEADER));
        }
        if (rows.size() - 1 > MAX_ROWS) {
            throw new InvalidAlertImportException("CSV contains more than " + MAX_ROWS + " alerts");
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
                throw rowError(rowNumber, "duplicate alert name also present on row " + previousRow);
            }

            String description = fields.get(1).trim().isEmpty() ? null : fields.get(1).trim();
            if (description != null && description.length() > 2000) {
                throw rowError(rowNumber, "description exceeds 2000 characters");
            }

            String templateKey = fields.get(2).trim();
            if (templateKey.isEmpty())
                throw rowError(rowNumber, "templateKey is required");

            String cronExpression = fields.get(3).trim();
            if (cronExpression.isEmpty())
                throw rowError(rowNumber, "cronExpression is required");

            if (cronExpression.length() > 255)
                throw rowError(rowNumber, "cronExpression exceeds 255 characters");

            boolean enabled = parseBoolean(fields.get(4), "enabled", rowNumber);
            boolean allowConcurrentExecutions = parseBoolean(fields.get(5), "allowConcurrentExecutions", rowNumber);
            List<ImportParameter> parameters = parseParameters(fields.get(6), rowNumber);
            List<ImportTag> tags = parseTags(fields.get(7), rowNumber);
            result.add(
                    new ImportRow(
                            rowNumber, name, description, templateKey, cronExpression,
                            enabled, allowConcurrentExecutions, parameters, tags
                    )
            );
        }
        return result;
    }

    private List<ImportParameter> parseParameters(String rawParameters, int rowNumber) {
        if (rawParameters.isBlank())
            return List.of();

        JsonNode parametersNode;
        try {
            parametersNode = jsonMapper.readTree(rawParameters);
        } catch (Exception exception) {
            throw rowError(rowNumber, "parameters is not valid JSON", exception);
        }
        if (parametersNode == null || !parametersNode.isArray()) {
            throw rowError(rowNumber, "parameters must be a JSON array");
        }

        Set<String> keys = new LinkedHashSet<>();
        List<ImportParameter> parameters = new ArrayList<>();
        for (JsonNode parameterNode : parametersNode) {
            if (!parameterNode.isObject())
                throw rowError(rowNumber, "each parameter must be a JSON object");

            JsonNode keyNode = parameterNode.get("key");
            JsonNode sourceNode = parameterNode.get("source");
            JsonNode valueNode = parameterNode.get("value");
            if (keyNode == null || !keyNode.isString()) {
                throw rowError(rowNumber, "each parameter requires a string key");
            }
            if (sourceNode == null || !sourceNode.isString()) {
                throw rowError(rowNumber, "each parameter requires a string source");
            }
            if (valueNode == null || !valueNode.isString()) {
                throw rowError(rowNumber, "each parameter requires a string value");
            }

            String key = keyNode.stringValue().trim();
            if (key.isEmpty() || key.length() > 255) {
                throw rowError(rowNumber, "parameter key must contain between 1 and 255 characters");
            }
            if (!keys.add(key.toLowerCase(Locale.ROOT))) {
                throw rowError(rowNumber, "parameter '" + key + "' is listed more than once");
            }

            AlertParameterSource source;
            try {
                source = AlertParameterSource.valueOf(sourceNode.stringValue().trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw rowError(rowNumber, "parameter '" + key + "' has an invalid source", exception);
            }

            String value = valueNode.stringValue();
            if (source != AlertParameterSource.TEXT && value.isBlank()) {
                throw rowError(rowNumber, "parameter '" + key + "' requires the referenced name");
            }
            parameters.add(new ImportParameter(key, source, source == AlertParameterSource.TEXT ? value : value.trim()));
        }
        return List.copyOf(parameters);
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

    private static boolean parseBoolean(String rawValue, String field, int rowNumber) {
        return switch (rawValue.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw rowError(rowNumber, field + " must be true or false");
        };
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize alert CSV", exception);
        }
    }

    private static List<List<String>> parseRows(String csv) {
        try {
            return CsvSupport.parseCsv(csv);
        } catch (IllegalArgumentException exception) {
            throw new InvalidAlertImportException(exception.getMessage(), exception);
        }
    }

    private static InvalidAlertImportException rowError(int row, String message) {
        return new InvalidAlertImportException("CSV row " + row + ": " + message);
    }

    private static InvalidAlertImportException rowError(int row, String message, Exception cause) {
        return new InvalidAlertImportException("CSV row " + row + ": " + message, cause);
    }

    record ImportRow(
        int rowNumber,
        String name,
        String description,
        String templateKey,
        String cronExpression,
        boolean enabled,
        boolean allowConcurrentExecutions,
        List<ImportParameter> parameters,
        List<ImportTag> tags
    ) {
    }

    record ImportParameter(
        String key,
        AlertParameterSource source,
        String value
    ) {
    }

    record ImportTag(
        String name,
        String color
    ) {
    }

    private record ExportParameter(
        String key,
        AlertParameterSource source,
        String value
    ) {
    }

    private record ExportTag(
        String name,
        String color
    ) {
    }
}
