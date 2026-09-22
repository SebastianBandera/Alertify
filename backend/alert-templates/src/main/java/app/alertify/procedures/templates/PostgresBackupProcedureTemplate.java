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
    nameKey = "procedures.template.postgresBackup.name",
    descriptionKey = "procedures.template.postgresBackup.description",
    tags = @ProcedureTemplateTag(nameKey = "procedures.templateTag.database", color = "#F59E0B"),
    sourcePath = "app/alertify/procedures/templates/PostgresBackupProcedureTemplate.java"
)
public final class PostgresBackupProcedureTemplate implements ProcedureEvaluator {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SQL_MEDIA_TYPE = "application/sql";
    private static final String GZIP_MEDIA_TYPE = "application/gzip";

    @ProcedureParameter(
        labelKey = "procedures.template.postgresBackup.credentials",
        descriptionKey = "procedures.template.postgresBackup.credentialsDescription",
        allowedSources = AlertParameterSource.SECRET,
        allowedSecretValueTypes = "DB_SECRET",
        order = 1
    )
    private final DatabaseCredentials credentials;

    @ProcedureParameter(
        labelKey = "procedures.template.postgresBackup.fileName",
        descriptionKey = "procedures.template.postgresBackup.fileNameDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        allowedConfigurationValueTypes = { "STRING", "EXPRESSION" },
        order = 2
    )
    private final String fileName;

    @ProcedureParameter(
        labelKey = "procedures.template.postgresBackup.gzipCompressionLevel",
        descriptionKey = "procedures.template.postgresBackup.gzipCompressionLevelDescription",
        options = { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" },
        bindingAllowed = false,
        defaultValue = "0",
        order = 3
    )
    private final int gzipCompressionLevel;

    @ProcedureParameter(
        labelKey = "procedures.template.postgresBackup.excludePrivileges",
        descriptionKey = "procedures.template.postgresBackup.excludePrivilegesDescription",
        options = { "true", "false" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 4
    )
    private final boolean excludePrivileges;

    @ProcedureParameter(
        labelKey = "procedures.template.postgresBackup.discardOwnership",
        descriptionKey = "procedures.template.postgresBackup.discardOwnershipDescription",
        options = { "true", "false" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 5
    )
    private final boolean discardOwnership;

    @OutputParam(value = "backup", order = 1)
    private ProcedureArtifactOutput backup;

    public PostgresBackupProcedureTemplate(DatabaseCredentials credentials, String fileName, int gzipCompressionLevel, boolean excludePrivileges, boolean discardOwnership) {
        this.credentials = credentials;
        this.fileName = fileName;
        this.gzipCompressionLevel = gzipCompressionLevel;
        this.excludePrivileges = excludePrivileges;
        this.discardOwnership = discardOwnership;
    }

    @Override
    public JsonNode execute(ProcedureExecutionContext context) throws Exception {
        if (credentials.engine() != DatabaseEngine.POSTGRESQL)
            throw new IllegalArgumentException("PostgresBackupProcedureTemplate accepts only PostgreSQL credentials");
        if (gzipCompressionLevel < 0 || gzipCompressionLevel > 9)
            throw new IllegalArgumentException("gzipCompressionLevel must be between 0 and 9");

        String logicalName = fileName(fileName, gzipCompressionLevel > 0);
        String mediaType = gzipCompressionLevel == 0 ? SQL_MEDIA_TYPE : GZIP_MEDIA_TYPE;
        Map<String, String> options = options(credentials.options());
        Path passwordFile = Files.createTempFile("alertify-pgpass-", ".conf");
        Path errorFile = Files.createTempFile("alertify-pg-dump-", ".err");
        try {
            Files.setPosixFilePermissions(passwordFile, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(errorFile, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(passwordFile, pgPass(credentials), StandardCharsets.UTF_8);

            List<String> command = pgDumpCommand(credentials, excludePrivileges, discardOwnership);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("PGPASSFILE", passwordFile.toString());
            if (options.containsKey("sslmode"))
                builder.environment().put("PGSSLMODE", options.get("sslmode"));
            if (options.containsKey("connect_timeout"))
                builder.environment().put("PGCONNECT_TIMEOUT", options.get("connect_timeout"));
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
                throw new IllegalStateException("pg_dump failed with exit code " + exit + diagnostic(errorFile));

            return JSON.createObjectNode().put("fileName", logicalName).put("mediaType", mediaType)
                    .put("size", output.count()).put("gzipCompressionLevel", gzipCompressionLevel)
                    .put("format", "plain-sql").put("encoding", "UTF8").put("dataStatements", "COPY")
                    .put("excludePrivileges", excludePrivileges).put("discardOwnership", discardOwnership);
        } finally {
            Files.deleteIfExists(passwordFile);
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

    static List<String> pgDumpCommand(DatabaseCredentials credentials, boolean excludePrivileges, boolean discardOwnership) {
        List<String> command = new ArrayList<>(List.of("pg_dump", "--format=plain", "--encoding=UTF8"));
        if (discardOwnership)
            command.add("--no-owner");
        if (excludePrivileges)
            command.add("--no-privileges");

        command.addAll(List.of("--host", credentials.host(), "--port", Integer.toString(credentials.port()),
                "--username", credentials.username(), "--dbname", credentials.database()));
        return List.copyOf(command);
    }

    private static Map<String, String> options(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank())
            return result;
        String normalized = raw.startsWith("?") ? raw.substring(1) : raw;
        for (String entry : normalized.split("&")) {
            String[] pair = entry.split("=", 2);
            if (pair.length != 2)
                throw new IllegalArgumentException("PostgreSQL options must use key=value syntax");
            String key = pair[0].trim().toLowerCase(Locale.ROOT);
            String value = pair[1].trim();
            switch (key) {
                case "sslmode" -> {
                    if (!List.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full").contains(value))
                        throw new IllegalArgumentException("Unsupported PostgreSQL sslmode");
                }
                case "connect_timeout" -> {
                    int seconds;
                    try { seconds = Integer.parseInt(value); }
                    catch (NumberFormatException exception) { throw new IllegalArgumentException("connect_timeout must be an integer", exception); }
                    if (seconds < 1 || seconds > 3600)
                        throw new IllegalArgumentException("connect_timeout must be between 1 and 3600 seconds");
                }
                default -> throw new IllegalArgumentException("Unsupported PostgreSQL option '" + key + "'");
            }
            if (result.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("PostgreSQL option '" + key + "' is duplicated");
        }
        return result;
    }

    private static String pgPass(DatabaseCredentials value) {
        return escape(value.host()) + ":" + value.port() + ":" + escape(value.database()) + ":"
                + escape(value.username()) + ":" + escape(value.password()) + System.lineSeparator();
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace(":", "\\:"); }

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
