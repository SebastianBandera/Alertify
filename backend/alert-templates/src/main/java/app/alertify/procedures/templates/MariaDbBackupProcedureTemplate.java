package app.alertify.procedures.templates;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.artifact.ProcedureArtifactOutput;
import app.alertify.procedures.template.annotation.OutputParam;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateTag;
import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.DatabaseEngine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ProcedureTemplate(
    nameKey = "procedures.template.mariaDbBackup.name",
    descriptionKey = "procedures.template.mariaDbBackup.description",
    tags = @ProcedureTemplateTag(nameKey = "procedures.templateTag.database", color = "#F59E0B"),
    sourcePath = "app/alertify/procedures/templates/MariaDbBackupProcedureTemplate.java"
)
public final class MariaDbBackupProcedureTemplate implements ProcedureEvaluator {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SQL_MEDIA_TYPE = "application/sql";
    private static final String GZIP_MEDIA_TYPE = "application/gzip";

    @ProcedureParameter(
        labelKey = "procedures.template.mariaDbBackup.credentials",
        descriptionKey = "procedures.template.mariaDbBackup.credentialsDescription",
        allowedSources = AlertParameterSource.SECRET,
        allowedSecretValueTypes = "DB_SECRET",
        order = 1
    )
    private final DatabaseCredentials credentials;

    @ProcedureParameter(
        labelKey = "procedures.template.mariaDbBackup.fileName",
        descriptionKey = "procedures.template.mariaDbBackup.fileNameDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        allowedConfigurationValueTypes = { "STRING", "EXPRESSION" },
        order = 2
    )
    private final String fileName;

    @ProcedureParameter(
        labelKey = "procedures.template.mariaDbBackup.gzipCompressionLevel",
        descriptionKey = "procedures.template.mariaDbBackup.gzipCompressionLevelDescription",
        options = { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" },
        bindingAllowed = false,
        defaultValue = "0",
        order = 3
    )
    private final int gzipCompressionLevel;

    @ProcedureParameter(
        labelKey = "procedures.template.mariaDbBackup.singleTransaction",
        descriptionKey = "procedures.template.mariaDbBackup.singleTransactionDescription",
        options = { "true", "false" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 4
    )
    private final boolean singleTransaction;

    @ProcedureParameter(
        labelKey = "procedures.template.mariaDbBackup.includeRoutines",
        descriptionKey = "procedures.template.mariaDbBackup.includeRoutinesDescription",
        options = { "true", "false" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 5
    )
    private final boolean includeRoutines;

    @OutputParam(value = "backup", order = 1)
    private ProcedureArtifactOutput backup;

    public MariaDbBackupProcedureTemplate(DatabaseCredentials credentials, String fileName, int gzipCompressionLevel, boolean singleTransaction, boolean includeRoutines) {
        this.credentials = credentials;
        this.fileName = fileName;
        this.gzipCompressionLevel = gzipCompressionLevel;
        this.singleTransaction = singleTransaction;
        this.includeRoutines = includeRoutines;
    }

    @Override
    public JsonNode execute(ProcedureExecutionContext context) throws Exception {
        if (credentials.engine() != DatabaseEngine.MARIADB)
            throw new IllegalArgumentException("MariaDbBackupProcedureTemplate accepts only MariaDB credentials");
        if (gzipCompressionLevel < 0 || gzipCompressionLevel > 9)
            throw new IllegalArgumentException("gzipCompressionLevel must be between 0 and 9");

        String logicalName = fileName(fileName, gzipCompressionLevel > 0);
        String mediaType = gzipCompressionLevel == 0 ? SQL_MEDIA_TYPE : GZIP_MEDIA_TYPE;
        Map<String, String> options = options(credentials.options());
        Path defaultsFile = Files.createTempFile("alertify-mariadb-", ".cnf");
        Path errorFile = Files.createTempFile("alertify-mariadb-dump-", ".err");
        try {
            Files.setPosixFilePermissions(defaultsFile, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(errorFile, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(defaultsFile, defaults(credentials), StandardCharsets.UTF_8);

            List<String> command = dumpCommand(credentials, defaultsFile, options, singleTransaction, includeRoutines);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(errorFile.toFile());

            Process process = builder.start();
            CountingOutputStream output;
            try (OutputStream artifact = backup.openStream(logicalName, mediaType)) {
                output = new CountingOutputStream(artifact);
                OutputStream payload = gzipCompressionLevel == 0 ? output : new LevelGzipOutputStream(output, gzipCompressionLevel);
                try {
                    process.getInputStream().transferTo(payload);
                    if (payload instanceof GZIPOutputStream gzip)
                        gzip.finish();

                    payload.flush();
                } catch (IOException exception) {
                    process.destroyForcibly();
                    throw exception;
                }
            }
            int exit = process.waitFor();
            if (exit != 0)
                throw new IllegalStateException("mariadb-dump failed with exit code " + exit + diagnostic(errorFile));

            return JSON.createObjectNode().put("fileName", logicalName).put("mediaType", mediaType)
                    .put("size", output.count()).put("gzipCompressionLevel", gzipCompressionLevel)
                    .put("format", "plain-sql").put("characterSet", "utf8mb4").put("dataStatements", "INSERT")
                    .put("singleTransaction", singleTransaction).put("includeRoutines", includeRoutines);
        } finally {
            Files.deleteIfExists(defaultsFile);
            Files.deleteIfExists(errorFile);
        }
    }

    private static String fileName(String value, boolean compressed) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("fileName must not be blank");
        String normalized = value.trim();
        if (normalized.equals(".") || normalized.equals("..") || normalized.contains("/") || normalized.contains("\\")
                || normalized.indexOf('\0') >= 0 || normalized.length() > 255)
            throw new IllegalArgumentException("fileName must be a logical file name without path components");

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (compressed) {
            if (lower.endsWith(".sql.gz"))
                return normalized;
            if (lower.endsWith(".sql"))
                return normalized + ".gz";

            return normalized + ".sql.gz";
        }
        return lower.endsWith(".sql") ? normalized : normalized + ".sql";
    }

    static List<String> dumpCommand(DatabaseCredentials credentials, Path defaultsFile, Map<String, String> options, boolean singleTransaction, boolean includeRoutines) {
        // --defaults-extra-file must be the first argument so the client honours it.
        List<String> command = new ArrayList<>(List.of("mariadb-dump", "--defaults-extra-file=" + defaultsFile,
                "--default-character-set=utf8mb4", "--hex-blob", "--quick", "--triggers"));
        if (singleTransaction)
            command.add("--single-transaction");
        if (includeRoutines)
            command.addAll(List.of("--routines", "--events"));

        String sslMode = options.get("sslmode");
        if (sslMode != null) {
            switch (sslMode) {
                case "disable" -> command.add("--skip-ssl");
                case "trust" -> command.addAll(List.of("--ssl", "--skip-ssl-verify-server-cert"));
                default -> command.addAll(List.of("--ssl", "--ssl-verify-server-cert"));
            }
        }
        if (options.containsKey("connecttimeout"))
            command.add("--connect-timeout=" + Math.max(1, (Long.parseLong(options.get("connecttimeout")) + 999) / 1000));

        // The database is passed as a positional argument (not --databases) so the dump omits
        // CREATE DATABASE/USE statements and restores into any target database.
        command.addAll(List.of("--host", credentials.host(), "--port", Integer.toString(credentials.port()),
                "--user", credentials.username(), credentials.database()));
        return List.copyOf(command);
    }

    static Map<String, String> options(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank())
            return result;
        String normalized = raw.startsWith("?") ? raw.substring(1) : raw;
        for (String entry : normalized.split("&")) {
            String[] pair = entry.split("=", 2);
            if (pair.length != 2)
                throw new IllegalArgumentException("MariaDB options must use key=value syntax");
            String key = pair[0].trim().toLowerCase(Locale.ROOT);
            String value = pair[1].trim();
            switch (key) {
                case "sslmode" -> {
                    if (!List.of("disable", "trust", "verify-ca", "verify-full").contains(value))
                        throw new IllegalArgumentException("Unsupported MariaDB sslMode");
                }
                case "connecttimeout" -> {
                    long millis;
                    try { millis = Long.parseLong(value); }
                    catch (NumberFormatException exception) { throw new IllegalArgumentException("connectTimeout must be an integer number of milliseconds", exception); }
                    if (millis < 1 || millis > 3_600_000)
                        throw new IllegalArgumentException("connectTimeout must be between 1 and 3600000 milliseconds");
                }
                default -> throw new IllegalArgumentException("Unsupported MariaDB option '" + pair[0].trim() + "'");
            }
            if (result.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("MariaDB option '" + pair[0].trim() + "' is duplicated");
        }
        return result;
    }

    /** Option file read by mariadb-dump; keeps the password out of the process arguments. */
    static String defaults(DatabaseCredentials value) {
        return "[client]" + System.lineSeparator()
                + "password=" + quote(value.password()) + System.lineSeparator();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String diagnostic(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            int length = Math.min(bytes.length, 4096);
            String value = new String(bytes, 0, length, StandardCharsets.UTF_8).replaceAll("[\\r\\n]+", " ").trim();
            return value.isEmpty() ? "" : ": " + value;
        } catch (IOException exception) {
            return "";
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        private CountingOutputStream(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int value) throws IOException { delegate.write(value); count++; }
        @Override public void write(byte[] value, int offset, int length) throws IOException { delegate.write(value, offset, length); count += length; }
        @Override public void flush() throws IOException { delegate.flush(); }
        private long count() { return count; }
    }

    private static final class LevelGzipOutputStream extends GZIPOutputStream {
        private LevelGzipOutputStream(OutputStream output, int level) throws IOException {
            super(output, 64 * 1024);
            def.setLevel(level);
        }
    }
}
