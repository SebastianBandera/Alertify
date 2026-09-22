package app.alertify.pipes.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import app.alertify.api.csv.CsvSupport;
import app.alertify.api.error.InvalidPipeImportException;
import app.alertify.pipes.model.Pipe;
import app.alertify.pipes.model.PipeStep;
import app.alertify.pipes.model.PipeStepType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class PipeCsvCodec {
    private static final List<String> HEADER = List.of("name", "description", "enabled", "allowConcurrentExecutions", "steps");
    private static final int MAX_ROWS = 10_000;
    private final JsonMapper jsonMapper;

    PipeCsvCodec(JsonMapper jsonMapper) { this.jsonMapper = jsonMapper; }

    byte[] write(List<Pipe> pipes) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        CsvSupport.appendRow(csv, HEADER);
        for (Pipe pipe : pipes) {
            List<ExportStep> steps = pipe.getSteps().stream().map(PipeCsvCodec::exportStep).toList();
            CsvSupport.appendRow(csv, List.of(pipe.getName(), nullable(pipe.getDescription()), Boolean.toString(pipe.isEnabled()),
                    Boolean.toString(pipe.isConcurrentExecutionAllowed()), json(steps)));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ExportStep exportStep(PipeStep step) {
        String resource = step.getStepType() == PipeStepType.ALERT ? step.getAlert().getName() : step.getProcedure().getName();
        List<ExportBinding> bindings = step.getBindings().stream().map(value -> new ExportBinding(
                value.getTargetParameter().getParameterKey(), value.getSourceStep().getStepKey(), value.getSourceOutput().getOutputKey())).toList();
        return new ExportStep(step.getStepKey(), step.getStepType(), resource, step.getTimeoutMillis(), step.getContinueOn(), bindings);
    }

    List<ImportRow> read(byte[] content) {
        String csv = new String(content, StandardCharsets.UTF_8);
        if (csv.startsWith("\uFEFF"))
            csv = csv.substring(1);

        List<List<String>> rows;
        try { rows = CsvSupport.parseCsv(csv); }
        catch (IllegalArgumentException exception) { throw new InvalidPipeImportException(exception.getMessage(), exception); }
        if (rows.isEmpty())
            throw new InvalidPipeImportException("The CSV file is empty");
        if (!rows.getFirst().equals(HEADER))
            throw new InvalidPipeImportException("CSV header must be exactly: " + String.join(",", HEADER));
        if (rows.size() - 1 > MAX_ROWS)
            throw new InvalidPipeImportException("CSV contains more than " + MAX_ROWS + " Pipes");

        Map<String, Integer> names = new LinkedHashMap<>();
        List<ImportRow> result = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> fields = rows.get(index);
            int row = index + 1;
            if (fields.size() != HEADER.size())
                throw error(row, "expected " + HEADER.size() + " columns");

            String name = fields.get(0).trim();
            if (name.isEmpty() || name.length() > 255)
                throw error(row, "name must contain between 1 and 255 characters");
            Integer previous = names.putIfAbsent(name.toLowerCase(Locale.ROOT), row);
            if (previous != null)
                throw error(row, "duplicate Pipe name also present on row " + previous);

            String description = fields.get(1).trim().isEmpty() ? null : fields.get(1).trim();
            if (description != null && description.length() > 4000)
                throw error(row, "description exceeds 4000 characters");

            result.add(new ImportRow(row, name, description, parseBoolean(fields.get(2), row, "enabled"),
                    parseBoolean(fields.get(3), row, "allowConcurrentExecutions"), steps(fields.get(4), row)));
        }
        return List.copyOf(result);
    }

    private List<ImportStep> steps(String raw, int row) {
        JsonNode node = tree(raw, "steps", row);
        if (node == null)
            return List.of();
        if (!node.isArray())
            throw error(row, "steps must be a JSON array");

        Set<String> keys = new LinkedHashSet<>();
        List<ImportStep> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isObject() || !string(value, "key") || !string(value, "type") || !string(value, "resource")
                    || !value.has("timeoutMillis") || !value.get("timeoutMillis").isIntegralNumber()
                    || !value.has("continueOn") || !value.get("continueOn").isArray()
                    || !value.has("bindings") || !value.get("bindings").isArray())
                throw error(row, "each step requires key, type, resource, timeoutMillis, continueOn, and bindings");

            String key = value.get("key").stringValue().trim();
            if (key.isEmpty() || key.length() > 255)
                throw error(row, "step key must contain between 1 and 255 characters");
            if (!keys.add(key))
                throw error(row, "step key '" + key + "' is duplicated");

            PipeStepType type;
            try { type = PipeStepType.valueOf(value.get("type").stringValue().trim().toUpperCase(Locale.ROOT)); }
            catch (RuntimeException exception) { throw error(row, "step '" + key + "' has an invalid type", exception); }
            String resource = value.get("resource").stringValue().trim();
            if (resource.isEmpty())
                throw error(row, "step '" + key + "' requires a resource name");
            long timeout = value.get("timeoutMillis").longValue();
            if (timeout <= 0)
                throw error(row, "step '" + key + "' timeoutMillis must be positive");

            Set<String> continueOn = strings(value.get("continueOn"), row, "step '" + key + "' continueOn");
            if (continueOn.isEmpty())
                continueOn = Set.of("SUCCESS");
            List<ImportBinding> bindings = bindings(value.get("bindings"), row, key);
            result.add(new ImportStep(key, type, resource, timeout, continueOn, bindings));
        }
        return List.copyOf(result);
    }

    private static List<ImportBinding> bindings(JsonNode node, int row, String stepKey) {
        List<ImportBinding> result = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();
        for (JsonNode value : node) {
            if (!value.isObject() || !string(value, "targetParameterKey") || !string(value, "sourceStepKey") || !string(value, "sourceOutputKey"))
                throw error(row, "each binding on step '" + stepKey + "' requires targetParameterKey, sourceStepKey, and sourceOutputKey");
            String target = value.get("targetParameterKey").stringValue().trim();
            String sourceStep = value.get("sourceStepKey").stringValue().trim();
            String sourceOutput = value.get("sourceOutputKey").stringValue().trim();
            if (target.isEmpty() || sourceStep.isEmpty() || sourceOutput.isEmpty())
                throw error(row, "binding keys on step '" + stepKey + "' cannot be empty");
            if (!targets.add(target))
                throw error(row, "target parameter '" + target + "' is bound more than once on step '" + stepKey + "'");
            result.add(new ImportBinding(target, sourceStep, sourceOutput));
        }
        return List.copyOf(result);
    }

    private static Set<String> strings(JsonNode node, int row, String field) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : node) {
            if (!value.isString())
                throw error(row, field + " must contain strings");
            result.add(value.stringValue().trim().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private JsonNode tree(String raw, String field, int row) {
        if (raw.isBlank())
            return null;
        try { return jsonMapper.readTree(raw); }
        catch (Exception exception) { throw error(row, field + " is not valid JSON", exception); }
    }

    private static boolean string(JsonNode node, String field) { return node.has(field) && node.get(field).isString(); }
    private static boolean parseBoolean(String value, int row, String field) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw error(row, field + " must be true or false");
        };
    }

    private String json(Object value) {
        try { return jsonMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize Pipe CSV", exception); }
    }

    private static String nullable(String value) { return value == null ? "" : value; }
    private static InvalidPipeImportException error(int row, String message) { return new InvalidPipeImportException("CSV row " + row + ": " + message); }
    private static InvalidPipeImportException error(int row, String message, Exception cause) { return new InvalidPipeImportException("CSV row " + row + ": " + message, cause); }

    record ImportRow(int rowNumber, String name, String description, boolean enabled, boolean allowConcurrentExecutions, List<ImportStep> steps) { }
    record ImportStep(String key, PipeStepType type, String resource, long timeoutMillis, Set<String> continueOn, List<ImportBinding> bindings) { }
    record ImportBinding(String targetParameterKey, String sourceStepKey, String sourceOutputKey) { }
    private record ExportStep(String key, PipeStepType type, String resource, long timeoutMillis, List<String> continueOn, List<ExportBinding> bindings) { }
    private record ExportBinding(String targetParameterKey, String sourceStepKey, String sourceOutputKey) { }
}
