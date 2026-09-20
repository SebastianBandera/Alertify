package app.alertify.hooks.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import app.alertify.api.csv.CsvSupport;
import app.alertify.api.error.InvalidHookImportException;
import app.alertify.hooks.api.HookImportError;
import app.alertify.hooks.model.Hook;
import app.alertify.hooks.model.HookMode;
import app.alertify.hooks.model.HookOutcome;
import app.alertify.hooks.model.HookTarget;
import app.alertify.hooks.model.HookTargetType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CSV format for hook definitions. Token secrets and targets are referenced by
 * name only. Rows that fail validation are reported instead of aborting the
 * whole file, so one bad hook does not block the rest.
 */
@Component
class HookCsvCodec {
    private static final String TARGET_PREFIX = "target '";
    private static final List<String> HEADER = List.of(
            "publicId", "name", "description", "enabled", "mode", "tokenSecret", "maxConcurrentInvocations", "rateLimitCount", "rateLimitWindowSeconds", "targets"
    );
    private static final int MAX_ROWS = 10_000;
    private final JsonMapper jsonMapper;

    HookCsvCodec(JsonMapper jsonMapper) { this.jsonMapper = jsonMapper; }

    byte[] write(List<Hook> hooks) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        CsvSupport.appendRow(csv, HEADER);
        for (Hook hook : hooks) {
            List<ExportTarget> targets = hook.getTargets().stream().map(HookCsvCodec::exportTarget).toList();
            CsvSupport.appendRow(csv, List.of(hook.getPublicId().toString(), hook.getName(), nullable(hook.getDescription()),
                    Boolean.toString(hook.isEnabled()), hook.getMode().name(),
                    hook.getTokenSecret() == null ? "" : hook.getTokenSecret().getName(),
                    nullable(hook.getMaxConcurrentInvocations()), nullable(hook.getRateLimitCount()),
                    nullable(hook.getRateLimitWindowSeconds()), json(targets)));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ExportTarget exportTarget(HookTarget target) {
        boolean alert = target.getTargetType() == HookTargetType.ALERT;
        return new ExportTarget(target.getTargetType(), alert ? target.getAlert().getName() : target.getProcedure().getName(),
                target.getContinueOn(), target.getBusyWaitTimeoutMillis());
    }

    ReadResult read(byte[] content) {
        String csv = new String(content, StandardCharsets.UTF_8);
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);

        List<List<String>> rows;

        try {
            rows = CsvSupport.parseCsv(csv);
        } catch (IllegalArgumentException exception) {
            throw new InvalidHookImportException(exception.getMessage(), exception);
        }
        if (rows.isEmpty()) throw new InvalidHookImportException("The CSV file is empty");

        if (!rows.getFirst().equals(HEADER))
            throw new InvalidHookImportException("CSV header must be exactly: " + String.join(",", HEADER));

        if (rows.size() - 1 > MAX_ROWS)
            throw new InvalidHookImportException("CSV contains more than " + MAX_ROWS + " hooks");

        Map<String, Integer> names = new LinkedHashMap<>();
        List<ImportRow> result = new ArrayList<>();
        List<HookImportError> errors = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> fields = rows.get(index);
            int row = index + 1;
            String name = fields.size() > 1 ? fields.get(1).trim() : "";

            try {
                ImportRow parsed = parse(fields, row, names);
                result.add(parsed);
            } catch (RowException exception) {
                errors.add(new HookImportError(row, name, exception.getMessage()));
            }
        }
        return new ReadResult(List.copyOf(result), List.copyOf(errors));
    }

    private ImportRow parse(List<String> fields, int row, Map<String, Integer> names) {
        if (fields.size() != HEADER.size()) throw new RowException("expected " + HEADER.size() + " columns");

        UUID publicId = null;
        String rawPublicId = fields.get(0).trim();

        if (!rawPublicId.isEmpty()) {
            try { publicId = UUID.fromString(rawPublicId); }
            catch (IllegalArgumentException exception) { throw new RowException("publicId must be a UUID"); }
        }
        String name = fields.get(1).trim();

        if (name.isEmpty() || name.length() > 255) throw new RowException("name must contain between 1 and 255 characters");

        Integer previous = names.putIfAbsent(name.toLowerCase(Locale.ROOT), row);

        if (previous != null) throw new RowException("duplicate hook name also present on row " + previous);

        String description = fields.get(2).trim().isEmpty() ? null : fields.get(2).trim();

        if (description != null && description.length() > 4000) throw new RowException("description exceeds 4000 characters");

        boolean enabled = parseBoolean(fields.get(3));
        HookMode mode;

        try {
            mode = HookMode.valueOf(fields.get(4).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new RowException("mode must be PARALLEL or SEQUENTIAL");
        }
        String tokenSecret = fields.get(5).trim().isEmpty() ? null : fields.get(5).trim();
        Integer maxConcurrentInvocations = parseInteger(fields.get(6), "maxConcurrentInvocations");
        Integer rateLimitCount = parseInteger(fields.get(7), "rateLimitCount");
        Integer rateLimitWindow = parseInteger(fields.get(8), "rateLimitWindowSeconds");

        if ((rateLimitCount == null) != (rateLimitWindow == null))
            throw new RowException("rateLimitCount and rateLimitWindowSeconds must be configured together");

        return new ImportRow(row, publicId, name, description, enabled, mode, tokenSecret, maxConcurrentInvocations,
                rateLimitCount, rateLimitWindow == null ? null : rateLimitWindow.longValue(), targets(fields.get(9)));
    }

    private List<ImportTarget> targets(String raw) {
        if (raw.isBlank()) return List.of();

        JsonNode node;

        try { node = jsonMapper.readTree(raw); }
        catch (Exception exception) { throw new RowException("targets is not valid JSON"); }

        if (!node.isArray()) throw new RowException("targets must be a JSON array");

        Set<String> unique = new LinkedHashSet<>();
        List<ImportTarget> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isObject() || !string(value, "type") || !string(value, "name"))
                throw new RowException("each target requires string type and name properties");

            HookTargetType type;

            try {
                type = HookTargetType.valueOf(value.get("type").stringValue().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new RowException("target type must be ALERT or PROCEDURE");
            }
            String name = value.get("name").stringValue().trim();
            if (name.isEmpty()) throw new RowException("target name is required");

            if (!unique.add(type + ":" + name.toLowerCase(Locale.ROOT)))
                throw new RowException(TARGET_PREFIX + name + "' is listed more than once");

            result.add(new ImportTarget(type, name, continueOn(value.get("continueOn"), name), busyWait(value.get("busyWaitTimeoutMillis"), name)));
        }
        return List.copyOf(result);
    }

    private static Set<HookOutcome> continueOn(JsonNode node, String target) {
        Set<HookOutcome> result = EnumSet.noneOf(HookOutcome.class);
        if (node == null || node.isNull()) return result;

        if (!node.isArray()) throw new RowException(TARGET_PREFIX + target + "' continueOn must be a JSON array");

        for (JsonNode value : node) {
            if (!value.isString()) throw new RowException(TARGET_PREFIX + target + "' continueOn must contain outcome names");

            try {
                result.add(HookOutcome.valueOf(value.stringValue().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new RowException(TARGET_PREFIX + target + "' has an invalid continueOn outcome");
            }
        }
        return result;
    }

    private static long busyWait(JsonNode node, String target) {
        if (node == null || node.isNull()) return HookTarget.DEFAULT_BUSY_WAIT_MILLIS;

        if (!node.isIntegralNumber() || node.longValue() <= 0)
            throw new RowException(TARGET_PREFIX + target + "' busyWaitTimeoutMillis must be a positive integer");

        return node.longValue();
    }

    private static boolean string(JsonNode node, String field) {
        return node.get(field) != null && node.get(field).isString();
    }

    private static boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new RowException("enabled must be true or false");
        };
    }

    private static Integer parseInteger(String value, String field) {
        if (value.isBlank()) return null;

        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) throw new RowException(field + " must be a positive integer");

            return parsed;
        } catch (NumberFormatException exception) {
            throw new RowException(field + " must be a positive integer");
        }
    }

    private String json(Object value) {
        try { return jsonMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize hook CSV", exception); }
    }

    private static String nullable(String value) { return value == null ? "" : value; }
    private static String nullable(Number value) { return value == null ? "" : value.toString(); }

    record ReadResult(List<ImportRow> rows, List<HookImportError> errors) { }
    record ImportRow(int rowNumber, UUID publicId, String name, String description, boolean enabled, HookMode mode, String tokenSecret, Integer maxConcurrentInvocations, Integer rateLimitCount, Long rateLimitWindowSeconds, List<ImportTarget> targets) { }
    record ImportTarget(HookTargetType type, String name, Set<HookOutcome> continueOn, long busyWaitTimeoutMillis) { }
    private record ExportTarget(HookTargetType type, String name, List<String> continueOn, long busyWaitTimeoutMillis) { }

    /** Row-level validation failure; collected into the import result rather than thrown to the caller. */
    static class RowException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RowException(String message) { super(message); }
    }
}
