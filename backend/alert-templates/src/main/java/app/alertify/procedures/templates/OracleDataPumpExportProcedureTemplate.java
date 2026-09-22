package app.alertify.procedures.templates;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.artifact.ProcedureArtifactOutput;
import app.alertify.procedures.template.annotation.OutputParam;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateTag;
import app.alertify.worker.contract.DatabaseConnections;
import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.DatabaseEngine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Logical Oracle export returned as an artifact without any client tooling: the server
 * runs a Data Pump export job ({@code DBMS_DATAPUMP}) into one of its {@code DIRECTORY}
 * objects and the worker streams the {@code .dmp} back through the same JDBC connection
 * (the file is loaded into a temporary BLOB with {@code DBMS_LOB.LOADFROMFILE} and read
 * through its locator, so there is no 2 GB limit). Data Pump ships with every edition,
 * including Free/XE; only {@code COMPRESSION=ALL} depends on the Advanced Compression
 * option on Enterprise Edition.
 */
@ProcedureTemplate(
    nameKey = "procedures.template.oracleDataPumpExport.name",
    descriptionKey = "procedures.template.oracleDataPumpExport.description",
    tags = @ProcedureTemplateTag(nameKey = "procedures.templateTag.database", color = "#F59E0B"),
    sourcePath = "app/alertify/procedures/templates/OracleDataPumpExportProcedureTemplate.java"
)
public final class OracleDataPumpExportProcedureTemplate implements ProcedureEvaluator {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DMP_MEDIA_TYPE = "application/octet-stream";
    private static final String ZIP_MEDIA_TYPE = "application/zip";
    private static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9_$#]{0,127}$");
    private static final int LOG_TAIL_CHARACTERS = 4000;
    private static final int INSUFFICIENT_PRIVILEGES = 31631;
    private static final int DIRECTORY_INVALID = 39087;
    private static final int FILE_NOT_FOUND = 22285;
    private static final int FILE_ACCESS_DENIED = 22288;

    @ProcedureParameter(
        labelKey = "procedures.template.oracleDataPumpExport.credentials",
        descriptionKey = "procedures.template.oracleDataPumpExport.credentialsDescription",
        allowedSources = AlertParameterSource.SECRET,
        allowedSecretValueTypes = "DB_SECRET",
        order = 1
    )
    private final DatabaseCredentials credentials;

    @ProcedureParameter(
        labelKey = "procedures.template.oracleDataPumpExport.fileName",
        descriptionKey = "procedures.template.oracleDataPumpExport.fileNameDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        allowedConfigurationValueTypes = { "STRING", "EXPRESSION" },
        order = 2
    )
    private final String fileName;

    @ProcedureParameter(
        labelKey = "procedures.template.oracleDataPumpExport.directoryName",
        descriptionKey = "procedures.template.oracleDataPumpExport.directoryNameDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        allowedConfigurationValueTypes = { "STRING", "EXPRESSION" },
        defaultValue = "DATA_PUMP_DIR",
        order = 3
    )
    private final String directoryName;

    @ProcedureParameter(
        labelKey = "procedures.template.oracleDataPumpExport.exportMode",
        descriptionKey = "procedures.template.oracleDataPumpExport.exportModeDescription",
        options = { "SCHEMA", "FULL" },
        bindingAllowed = false,
        defaultValue = "SCHEMA",
        order = 4
    )
    private final String exportMode;

    @ProcedureParameter(
        labelKey = "procedures.template.oracleDataPumpExport.schemas",
        descriptionKey = "procedures.template.oracleDataPumpExport.schemasDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        allowedConfigurationValueTypes = { "STRING", "EXPRESSION" },
        required = false,
        order = 5
    )
    private final String schemas;

    @ProcedureParameter(
        labelKey = "procedures.template.oracleDataPumpExport.dataPumpCompression",
        descriptionKey = "procedures.template.oracleDataPumpExport.dataPumpCompressionDescription",
        options = { "METADATA_ONLY", "NONE", "ALL" },
        bindingAllowed = false,
        defaultValue = "METADATA_ONLY",
        order = 6
    )
    private final String dataPumpCompression;

    @ProcedureParameter(
        labelKey = "procedures.template.oracleDataPumpExport.zipCompressionLevel",
        descriptionKey = "procedures.template.oracleDataPumpExport.zipCompressionLevelDescription",
        options = { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" },
        bindingAllowed = false,
        defaultValue = "0",
        order = 7
    )
    private final int zipCompressionLevel;

    @ProcedureParameter(
        labelKey = "procedures.template.oracleDataPumpExport.deleteFromServer",
        descriptionKey = "procedures.template.oracleDataPumpExport.deleteFromServerDescription",
        options = { "true", "false" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 8
    )
    private final boolean deleteFromServer;

    @OutputParam(value = "backup", order = 1)
    private ProcedureArtifactOutput backup;

    public OracleDataPumpExportProcedureTemplate(DatabaseCredentials credentials, String fileName, String directoryName, String exportMode, String schemas, String dataPumpCompression, int zipCompressionLevel, boolean deleteFromServer) {
        this.credentials = credentials;
        this.fileName = fileName;
        this.directoryName = directoryName;
        this.exportMode = exportMode;
        this.schemas = schemas;
        this.dataPumpCompression = dataPumpCompression;
        this.zipCompressionLevel = zipCompressionLevel;
        this.deleteFromServer = deleteFromServer;
    }

    @Override
    public JsonNode execute(ProcedureExecutionContext context) throws Exception {
        if (credentials.engine() != DatabaseEngine.ORACLE)
            throw new IllegalArgumentException("OracleDataPumpExportProcedureTemplate accepts only Oracle credentials");
        if (zipCompressionLevel < 0 || zipCompressionLevel > 9)
            throw new IllegalArgumentException("zipCompressionLevel must be between 0 and 9");
        if (!List.of("SCHEMA", "FULL").contains(exportMode))
            throw new IllegalArgumentException("exportMode must be SCHEMA or FULL");
        if (!List.of("NONE", "METADATA_ONLY", "ALL").contains(dataPumpCompression))
            throw new IllegalArgumentException("dataPumpCompression must be NONE, METADATA_ONLY or ALL");

        String baseName = baseName(fileName);
        String directory = validateIdentifier(directoryName, "directoryName");
        String dumpFile = baseName + ".dmp";
        String logFile = baseName + ".log";
        String jobName = jobName(context);
        try (Connection connection = DatabaseConnections.open(credentials, LOGIN_TIMEOUT)) {
            List<String> schemaList = "SCHEMA".equals(exportMode) ? schemaList(connection, schemas) : List.of();
            String jobState = runExport(connection, jobName, directory, dumpFile, logFile, schemaList);
            String log = readLogTail(connection, directory, logFile);
            if (!"COMPLETED".equals(jobState))
                throw new IllegalStateException("EXPORT_NOT_COMPLETED: Data Pump job " + jobName + " finished with state " + jobState
                        + (log == null ? "" : ": " + log));

            boolean zipped = zipCompressionLevel > 0;
            String artifactName = baseName + (zipped ? ".zip" : ".dmp");
            String mediaType = zipped ? ZIP_MEDIA_TYPE : DMP_MEDIA_TYPE;
            Transfer transfer;
            try (OutputStream artifact = backup.openStream(artifactName, mediaType)) {
                transfer = zipped
                        ? transferZip(connection, directory, dumpFile, artifact)
                        : transferSingle(connection, directory, dumpFile, artifact);
            }
            if (transfer.serverBytes() != transfer.declaredBytes())
                throw new IllegalStateException("EXPORT_SIZE_MISMATCH: read " + transfer.serverBytes()
                        + " bytes but the dump file declares " + transfer.declaredBytes());

            boolean deleted = deleteFromServer && removeServerFiles(connection, directory, dumpFile, logFile);

            ObjectNode result = JSON.createObjectNode().put("fileName", artifactName).put("mediaType", mediaType)
                    .put("size", transfer.artifactBytes()).put("sha256", transfer.sha256())
                    .put("dumpSize", transfer.serverBytes()).put("jobName", jobName).put("jobState", jobState)
                    .put("exportMode", exportMode).put("directoryName", directory).put("dumpFile", dumpFile)
                    .put("logFile", logFile).put("dataPumpCompression", dataPumpCompression)
                    .put("zipCompressionLevel", zipCompressionLevel).put("serverFileDeleted", deleted)
                    .put("logErrorCount", log == null ? 0 : countErrors(log));
            var schemaNode = result.putArray("schemas");
            schemaList.forEach(schemaNode::add);
            if (log == null)
                result.putNull("serverLog");
            else
                result.put("serverLog", log);
            return result;
        }
    }

    private String runExport(Connection connection, String jobName, String directory, String dumpFile, String logFile, List<String> schemaList) throws SQLException {
        boolean schemaMode = !schemaList.isEmpty();
        try (CallableStatement statement = connection.prepareCall(exportBlock(schemaMode))) {
            int index = 1;
            statement.setString(index++, exportMode);
            statement.setString(index++, jobName);
            statement.setString(index++, dumpFile);
            statement.setString(index++, directory);
            statement.setString(index++, logFile);
            statement.setString(index++, directory);
            if (schemaMode)
                statement.setString(index++, schemaExpression(schemaList));
            statement.setString(index++, dataPumpCompression);
            statement.registerOutParameter(index, Types.VARCHAR);
            statement.execute();
            return statement.getString(index);
        } catch (SQLException exception) {
            throw switch (exception.getErrorCode()) {
                case INSUFFICIENT_PRIVILEGES -> failure("EXPORT_PERMISSION_DENIED", "the user lacks the privileges for this export mode (DATAPUMP_EXP_FULL_DATABASE for FULL or foreign schemas)", exception);
                case DIRECTORY_INVALID -> failure("DIRECTORY_INVALID", "the DIRECTORY object does not exist or the user has no READ/WRITE grant on it", exception);
                default -> failure("EXPORT_FAILED", "DBMS_DATAPUMP export failed", exception);
            };
        }
    }

    /** Anonymous block that runs the whole export synchronously and returns the final job state. */
    static String exportBlock(boolean schemaMode) {
        return """
                DECLARE
                  h NUMBER;
                  s VARCHAR2(30);
                BEGIN
                  h := DBMS_DATAPUMP.OPEN(operation => 'EXPORT', job_mode => ?, job_name => ?, version => 'LATEST');
                  BEGIN
                    DBMS_DATAPUMP.ADD_FILE(handle => h, filename => ?, directory => ?, filetype => DBMS_DATAPUMP.KU$_FILE_TYPE_DUMP_FILE, reusefile => 1);
                    DBMS_DATAPUMP.ADD_FILE(handle => h, filename => ?, directory => ?, filetype => DBMS_DATAPUMP.KU$_FILE_TYPE_LOG_FILE, reusefile => 1);
                """
                + (schemaMode ? "    DBMS_DATAPUMP.METADATA_FILTER(handle => h, name => 'SCHEMA_EXPR', value => ?);\n" : "")
                + """
                    DBMS_DATAPUMP.SET_PARAMETER(handle => h, name => 'COMPRESSION', value => ?);
                    DBMS_DATAPUMP.START_JOB(h);
                    DBMS_DATAPUMP.WAIT_FOR_JOB(h, s);
                  EXCEPTION
                    WHEN OTHERS THEN
                      BEGIN DBMS_DATAPUMP.STOP_JOB(h, 1, 0); EXCEPTION WHEN OTHERS THEN NULL; END;
                      RAISE;
                  END;
                  ? := s;
                END;""";
    }

    static String schemaExpression(List<String> schemaList) {
        StringBuilder expression = new StringBuilder("IN (");
        for (int index = 0; index < schemaList.size(); index++)
            expression.append(index == 0 ? "" : ", ").append('\'').append(schemaList.get(index)).append('\'');

        return expression.append(')').toString();
    }

    private static List<String> schemaList(Connection connection, String configured) throws SQLException {
        List<String> result = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            for (String entry : configured.split(","))
                if (!entry.isBlank())
                    result.add(validateIdentifier(entry, "schemas"));
        }
        if (result.isEmpty())
            result.add(currentSchema(connection));

        return List.copyOf(result);
    }

    private static String currentSchema(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM dual");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getString(1) == null)
                throw new IllegalStateException("EXPORT_FAILED: could not determine the current schema");

            return rows.getString(1);
        }
    }

    static String validateIdentifier(String value, String parameter) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(normalized).matches())
            throw new IllegalArgumentException(parameter + " must be a plain Oracle identifier (letters, digits, _ $ #)");

        return normalized;
    }

    static String baseName(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("fileName must not be blank");
        String normalized = value.trim();
        if (normalized.equals(".") || normalized.equals("..") || normalized.contains("/") || normalized.contains("\\")
                || normalized.contains("'") || normalized.chars().anyMatch(Character::isISOControl) || normalized.length() > 200)
            throw new IllegalArgumentException("fileName must be a logical file name without path components or quotes");

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".dmp") || lower.endsWith(".zip"))
            normalized = normalized.substring(0, normalized.length() - 4);
        if (normalized.isBlank())
            throw new IllegalArgumentException("fileName must not be only an extension");

        return normalized;
    }

    static String jobName(ProcedureExecutionContext context) {
        return "ALERTIFY_" + Long.toHexString(context.now().toEpochMilli()).toUpperCase(Locale.ROOT);
    }

    private Transfer transferSingle(Connection connection, String directory, String dumpFile, OutputStream artifact) throws Exception {
        HashingOutputStream output = new HashingOutputStream(artifact);
        ServerFile read = readServerFile(connection, directory, dumpFile, output);
        output.flush();
        return new Transfer(read.bytes(), read.declaredLength(), output.count(), output.sha256());
    }

    private Transfer transferZip(Connection connection, String directory, String dumpFile, OutputStream artifact) throws Exception {
        HashingOutputStream output = new HashingOutputStream(artifact);
        ServerFile read;
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.setLevel(zipCompressionLevel == 0 ? Deflater.NO_COMPRESSION : zipCompressionLevel);
            zip.putNextEntry(new ZipEntry(dumpFile));
            read = readServerFile(connection, directory, dumpFile, zip);
            zip.closeEntry();
        }
        return new Transfer(read.bytes(), read.declaredLength(), output.count(), output.sha256());
    }

    /**
     * Loads the directory file into a temporary BLOB on the server and streams it through the
     * LOB locator, which the driver fetches in chunks. The temporary LOB is freed afterwards.
     */
    private static ServerFile readServerFile(Connection connection, String directory, String file, OutputStream target) throws SQLException, IOException {
        String block = """
                DECLARE
                  f BFILE := BFILENAME(?, ?);
                  b BLOB;
                BEGIN
                  DBMS_LOB.FILEOPEN(f, DBMS_LOB.FILE_READONLY);
                  DBMS_LOB.CREATETEMPORARY(b, TRUE, DBMS_LOB.SESSION);
                  DBMS_LOB.LOADFROMFILE(b, f, DBMS_LOB.GETLENGTH(f));
                  ? := DBMS_LOB.GETLENGTH(f);
                  DBMS_LOB.FILECLOSE(f);
                  ? := b;
                END;""";
        try (CallableStatement statement = connection.prepareCall(block)) {
            statement.setString(1, directory);
            statement.setString(2, file);
            statement.registerOutParameter(3, Types.NUMERIC);
            statement.registerOutParameter(4, Types.BLOB);
            statement.execute();
            long declared = statement.getLong(3);
            Blob blob = statement.getBlob(4);
            if (blob == null)
                throw new IllegalStateException("EXPORT_FILE_MISSING: the server returned no data for " + file);

            try (InputStream stream = blob.getBinaryStream()) {
                return new ServerFile(stream.transferTo(target), declared);
            } finally {
                try { blob.free(); } catch (SQLException ignored) { /* temporary LOB dies with the session anyway */ }
            }
        } catch (SQLException exception) {
            throw switch (exception.getErrorCode()) {
                case FILE_NOT_FOUND -> failure("EXPORT_FILE_MISSING", "the server cannot find " + file + " in directory " + directory, exception);
                case FILE_ACCESS_DENIED -> failure("DIRECTORY_ACCESS_DENIED", "the server cannot read " + file + " (check the directory grant and filesystem permissions)", exception);
                default -> failure("EXPORT_READ_FAILED", "reading " + file + " through DBMS_LOB failed", exception);
            };
        }
    }

    private static String readLogTail(Connection connection, String directory, String logFile) {
        try {
            var buffer = new java.io.ByteArrayOutputStream();
            readServerFile(connection, directory, logFile, buffer);
            String text = buffer.toString(StandardCharsets.UTF_8).replaceAll("\\r\\n?", "\n").trim();
            return text.length() <= LOG_TAIL_CHARACTERS ? text : text.substring(text.length() - LOG_TAIL_CHARACTERS);
        } catch (Exception exception) {
            // The log is diagnostic only; never fail the export because it cannot be read.
            return null;
        }
    }

    static int countErrors(String log) {
        int count = 0;
        for (String line : log.split("\n"))
            if (line.startsWith("ORA-"))
                count++;

        return count;
    }

    private static boolean removeServerFiles(Connection connection, String directory, String... files) {
        boolean deleted = true;
        for (String file : files) {
            try (CallableStatement statement = connection.prepareCall("BEGIN UTL_FILE.FREMOVE(?, ?); END;")) {
                statement.setString(1, directory);
                statement.setString(2, file);
                statement.execute();
            } catch (SQLException exception) {
                // Best effort: reusefile => 1 overwrites the files on the next run anyway.
                deleted = false;
            }
        }
        return deleted;
    }

    private static IllegalStateException failure(String code, String reason, SQLException exception) {
        String message = exception.getMessage() == null ? "" : ": " + exception.getMessage().replaceAll("[\\r\\n]+", " ").trim();
        return new IllegalStateException(code + ": " + reason + " (ORA-" + exception.getErrorCode() + ")" + message, exception);
    }

    private record ServerFile(long bytes, long declaredLength) { }
    private record Transfer(long serverBytes, long declaredBytes, long artifactBytes, String sha256) { }

    private static final class HashingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final MessageDigest digest;
        private long count;

        private HashingOutputStream(OutputStream delegate) throws Exception {
            this.delegate = delegate;
            this.digest = MessageDigest.getInstance("SHA-256");
        }
        @Override public void write(int value) throws IOException { delegate.write(value); digest.update((byte) value); count++; }
        @Override public void write(byte[] value, int offset, int length) throws IOException { delegate.write(value, offset, length); digest.update(value, offset, length); count += length; }
        @Override public void flush() throws IOException { delegate.flush(); }
        private long count() { return count; }
        private String sha256() { return HexFormat.of().formatHex(digest.digest()); }
    }
}
