package app.alertify.procedures.templates;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Native SQL Server backup returned as an artifact without any client tooling:
 * the server writes the {@code .bak} on its own disk ({@code BACKUP DATABASE ... TO DISK})
 * and the worker streams it back through the same JDBC connection with
 * {@code OPENROWSET(BULK ..., SINGLE_BLOB)}. On Windows the login needs ADMINISTER BULK
 * OPERATIONS for that read; SQL Server on Linux only allows it for sysadmin logins
 * (error 4860 otherwise). Each server file is limited to 2 GB by
 * {@code varbinary(max)}, so larger databases must be striped into several files, in
 * which case the artifact is a stored (uncompressed) zip containing every stripe.
 */
@ProcedureTemplate(
    nameKey = "procedures.template.sqlServerNativeBackup.name",
    descriptionKey = "procedures.template.sqlServerNativeBackup.description",
    tags = @ProcedureTemplateTag(nameKey = "procedures.templateTag.database", color = "#F59E0B"),
    sourcePath = "app/alertify/procedures/templates/SqlServerNativeBackupProcedureTemplate.java"
)
public final class SqlServerNativeBackupProcedureTemplate implements ProcedureEvaluator {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BAK_MEDIA_TYPE = "application/octet-stream";
    private static final String ZIP_MEDIA_TYPE = "application/zip";
    private static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(30);
    private static final long SINGLE_BLOB_LIMIT = 2_147_483_647L;
    private static final int MAX_STRIPES = 64;
    private static final int PERMISSION_DENIED = 262;
    private static final int CANNOT_OPEN_BACKUP_DEVICE = 3201;
    private static final int BULK_PERMISSION_DENIED = 4834;
    private static final int BULK_FILE_NOT_FOUND = 4860;

    @ProcedureParameter(
        labelKey = "procedures.template.sqlServerNativeBackup.credentials",
        descriptionKey = "procedures.template.sqlServerNativeBackup.credentialsDescription",
        allowedSources = AlertParameterSource.SECRET,
        allowedSecretValueTypes = "DB_SECRET",
        order = 1
    )
    private final DatabaseCredentials credentials;

    @ProcedureParameter(
        labelKey = "procedures.template.sqlServerNativeBackup.fileName",
        descriptionKey = "procedures.template.sqlServerNativeBackup.fileNameDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        allowedConfigurationValueTypes = { "STRING", "EXPRESSION" },
        order = 2
    )
    private final String fileName;

    @ProcedureParameter(
        labelKey = "procedures.template.sqlServerNativeBackup.serverBackupDirectory",
        descriptionKey = "procedures.template.sqlServerNativeBackup.serverBackupDirectoryDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        allowedConfigurationValueTypes = { "STRING", "EXPRESSION" },
        required = false,
        order = 3
    )
    private final String serverBackupDirectory;

    @ProcedureParameter(
        labelKey = "procedures.template.sqlServerNativeBackup.stripes",
        descriptionKey = "procedures.template.sqlServerNativeBackup.stripesDescription",
        defaultValue = "1",
        order = 4
    )
    private final int stripes;

    @ProcedureParameter(
        labelKey = "procedures.template.sqlServerNativeBackup.zipCompressionLevel",
        descriptionKey = "procedures.template.sqlServerNativeBackup.zipCompressionLevelDescription",
        options = { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" },
        bindingAllowed = false,
        defaultValue = "0",
        order = 5
    )
    private final int zipCompressionLevel;

    @ProcedureParameter(
        labelKey = "procedures.template.sqlServerNativeBackup.compression",
        descriptionKey = "procedures.template.sqlServerNativeBackup.compressionDescription",
        options = { "true", "false" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 6
    )
    private final boolean compression;

    @ProcedureParameter(
        labelKey = "procedures.template.sqlServerNativeBackup.checksum",
        descriptionKey = "procedures.template.sqlServerNativeBackup.checksumDescription",
        options = { "true", "false" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 7
    )
    private final boolean checksum;

    @ProcedureParameter(
        labelKey = "procedures.template.sqlServerNativeBackup.copyOnly",
        descriptionKey = "procedures.template.sqlServerNativeBackup.copyOnlyDescription",
        options = { "true", "false" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 8
    )
    private final boolean copyOnly;

    @ProcedureParameter(
        labelKey = "procedures.template.sqlServerNativeBackup.deleteFromServer",
        descriptionKey = "procedures.template.sqlServerNativeBackup.deleteFromServerDescription",
        options = { "true", "false" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 9
    )
    private final boolean deleteFromServer;

    @OutputParam(value = "backup", order = 1)
    private ProcedureArtifactOutput backup;

    public SqlServerNativeBackupProcedureTemplate(DatabaseCredentials credentials, String fileName, String serverBackupDirectory, int stripes, int zipCompressionLevel, boolean compression, boolean checksum, boolean copyOnly, boolean deleteFromServer) {
        this.credentials = credentials;
        this.fileName = fileName;
        this.serverBackupDirectory = serverBackupDirectory;
        this.stripes = stripes;
        this.zipCompressionLevel = zipCompressionLevel;
        this.compression = compression;
        this.checksum = checksum;
        this.copyOnly = copyOnly;
        this.deleteFromServer = deleteFromServer;
    }

    @Override
    public JsonNode execute(ProcedureExecutionContext context) throws Exception {
        if (credentials.engine() != DatabaseEngine.SQL_SERVER)
            throw new IllegalArgumentException("SqlServerNativeBackupProcedureTemplate accepts only SQL Server credentials");
        if (stripes < 1 || stripes > MAX_STRIPES)
            throw new IllegalArgumentException("stripes must be between 1 and " + MAX_STRIPES);
        if (zipCompressionLevel < 0 || zipCompressionLevel > 9)
            throw new IllegalArgumentException("zipCompressionLevel must be between 0 and 9");

        String baseName = baseName(fileName);
        String backupName = "Alertify " + credentials.database() + " " + DateTimeFormatter.ISO_INSTANT.format(context.now());
        try (Connection connection = DatabaseConnections.open(credentials, LOGIN_TIMEOUT)) {
            String directory = serverBackupDirectory == null || serverBackupDirectory.isBlank()
                    ? defaultBackupDirectory(connection) : validateDirectory(serverBackupDirectory);
            List<String> serverFiles = serverFiles(directory, baseName, stripes);

            String serverMessage = runBackup(connection, backupName, serverFiles);
            BackupSet backupSet = backupSet(connection, backupName);
            if (backupSet != null && backupSet.sizeOnDisk() / stripes > SINGLE_BLOB_LIMIT)
                throw new IllegalStateException("BACKUP_FILE_TOO_LARGE: the backup occupies " + backupSet.sizeOnDisk()
                        + " bytes across " + stripes + " file(s); OPENROWSET reads at most 2 GB per file, increase stripes");

            boolean zipped = stripes > 1 || zipCompressionLevel > 0;
            String artifactName = baseName + (zipped ? ".zip" : ".bak");
            String mediaType = zipped ? ZIP_MEDIA_TYPE : BAK_MEDIA_TYPE;
            Transfer transfer;
            try (OutputStream artifact = backup.openStream(artifactName, mediaType)) {
                transfer = zipped
                        ? transferZip(connection, serverFiles, artifact)
                        : transferSingle(connection, serverFiles.getFirst(), artifact);
            }
            // Backup files are written in whole blocks, so the on-disk size is at least msdb's compressed_backup_size.
            if (transfer.serverBytes() <= 0 || (backupSet != null && transfer.serverBytes() < backupSet.sizeOnDisk()))
                throw new IllegalStateException("BACKUP_SIZE_MISMATCH: read " + transfer.serverBytes()
                        + " bytes from the server but msdb reports " + (backupSet == null ? "unknown" : backupSet.sizeOnDisk()));

            boolean deleted = deleteFromServer && deleteServerFiles(connection, serverFiles);

            ObjectNode result = JSON.createObjectNode().put("fileName", artifactName).put("mediaType", mediaType)
                    .put("size", transfer.artifactBytes()).put("sha256", transfer.sha256())
                    .put("databaseName", credentials.database()).put("serverBackupDirectory", directory)
                    .put("stripes", stripes).put("zipCompressionLevel", zipCompressionLevel)
                    .put("compression", compression).put("checksum", checksum)
                    .put("copyOnly", copyOnly).put("serverFileDeleted", deleted).put("serverMessage", serverMessage);
            ArrayNode files = result.putArray("serverFiles");
            serverFiles.forEach(files::add);
            if (backupSet != null) {
                result.put("serverName", backupSet.serverName()).put("serverVersion", backupSet.serverVersion())
                        .put("backupFinishedAt", backupSet.finishedAt()).put("backupSize", backupSet.size())
                        .put("sizeOnDisk", backupSet.sizeOnDisk());
            } else {
                result.putNull("backupFinishedAt");
            }
            return result;
        }
    }

    private String runBackup(Connection connection, String backupName, List<String> serverFiles) throws SQLException {
        String sql = backupStatement(credentials.database(), serverFiles.size(), compression, checksum, copyOnly);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, backupName);
            for (int index = 0; index < serverFiles.size(); index++)
                statement.setString(index + 2, serverFiles.get(index));

            drain(statement, statement.execute());
            return lastWarning(statement);
        } catch (SQLException exception) {
            throw switch (exception.getErrorCode()) {
                case PERMISSION_DENIED -> failure("BACKUP_PERMISSION_DENIED", "the login lacks BACKUP DATABASE permission", exception);
                case CANNOT_OPEN_BACKUP_DEVICE -> failure("BACKUP_DIRECTORY_UNREACHABLE", "SQL Server cannot write to the backup directory", exception);
                default -> failure("BACKUP_FAILED", "BACKUP DATABASE failed", exception);
            };
        }
    }

    static String backupStatement(String database, int stripes, boolean compression, boolean checksum, boolean copyOnly) {
        StringBuilder sql = new StringBuilder("DECLARE @name NVARCHAR(128) = ?; BACKUP DATABASE ")
                .append(quoteIdentifier(database)).append(" TO");
        for (int index = 0; index < stripes; index++)
            sql.append(index == 0 ? " DISK = ?" : ", DISK = ?");

        sql.append(" WITH INIT, FORMAT, NAME = @name");
        if (compression)
            sql.append(", COMPRESSION");
        if (checksum)
            sql.append(", CHECKSUM");
        if (copyOnly)
            sql.append(", COPY_ONLY");

        return sql.toString();
    }

    static String quoteIdentifier(String value) {
        return "[" + value.replace("]", "]]") + "]";
    }

    static String quoteLiteral(String value) {
        return "N'" + value.replace("'", "''") + "'";
    }

    private static String defaultBackupDirectory(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT CONVERT(NVARCHAR(4000), SERVERPROPERTY('InstanceDefaultBackupPath'))")) {
            String value = rows.next() ? rows.getString(1) : null;
            if (value == null || value.isBlank())
                throw new IllegalStateException("BACKUP_DIRECTORY_UNKNOWN: the instance does not expose a default backup path; set serverBackupDirectory");

            return validateDirectory(value);
        }
    }

    static String validateDirectory(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 1024 || normalized.contains("..") || normalized.contains("'")
                || normalized.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("serverBackupDirectory must be an absolute server path without '..' or quotes");

        return normalized;
    }

    static List<String> serverFiles(String directory, String baseName, int stripes) {
        String separator = directory.contains("\\") && !directory.contains("/") ? "\\" : "/";
        String prefix = directory.endsWith("/") || directory.endsWith("\\") ? directory : directory + separator;
        List<String> files = new ArrayList<>(stripes);
        if (stripes == 1) {
            files.add(prefix + baseName + ".bak");
        } else {
            for (int index = 1; index <= stripes; index++)
                files.add(prefix + baseName + "." + index + ".bak");
        }
        return List.copyOf(files);
    }

    static String baseName(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("fileName must not be blank");
        String normalized = value.trim();
        if (normalized.equals(".") || normalized.equals("..") || normalized.contains("/") || normalized.contains("\\")
                || normalized.contains("'") || normalized.chars().anyMatch(Character::isISOControl) || normalized.length() > 200)
            throw new IllegalArgumentException("fileName must be a logical file name without path components or quotes");

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".bak"))
            normalized = normalized.substring(0, normalized.length() - 4);
        else if (lower.endsWith(".zip"))
            normalized = normalized.substring(0, normalized.length() - 4);
        if (normalized.isBlank())
            throw new IllegalArgumentException("fileName must not be only an extension");

        return normalized;
    }

    private BackupSet backupSet(Connection connection, String backupName) {
        String sql = """
                SELECT TOP 1 bs.backup_size, bs.compressed_backup_size, bs.backup_finish_date, bs.server_name,
                       bs.software_major_version, bs.software_minor_version, bs.software_build_version
                FROM msdb.dbo.backupset bs
                WHERE bs.database_name = ? AND bs.name = ?
                ORDER BY bs.backup_finish_date DESC, bs.backup_set_id DESC""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, credentials.database());
            statement.setString(2, backupName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next())
                    return null;

                long size = rows.getLong(1);
                long sizeOnDisk = rows.getLong(2);
                if (rows.wasNull() || sizeOnDisk <= 0)
                    sizeOnDisk = size;
                java.sql.Timestamp finished = rows.getTimestamp(3);
                String version = rows.getInt(5) + "." + rows.getInt(6) + "." + rows.getInt(7);
                return new BackupSet(size, sizeOnDisk, finished == null ? null : finished.toInstant().toString(), rows.getString(4), version);
            }
        } catch (SQLException exception) {
            // msdb history is optional: the login may not be allowed to read it.
            return null;
        }
    }

    private Transfer transferSingle(Connection connection, String serverFile, OutputStream artifact) throws Exception {
        HashingOutputStream output = new HashingOutputStream(artifact);
        readServerFile(connection, serverFile, output);
        output.flush();
        return new Transfer(output.count(), output.count(), output.sha256());
    }

    private Transfer transferZip(Connection connection, List<String> serverFiles, OutputStream artifact) throws Exception {
        HashingOutputStream output = new HashingOutputStream(artifact);
        long serverBytes = 0;
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.setLevel(zipCompressionLevel == 0 ? Deflater.NO_COMPRESSION : zipCompressionLevel);
            for (String serverFile : serverFiles) {
                zip.putNextEntry(new ZipEntry(leafName(serverFile)));
                serverBytes += readServerFile(connection, serverFile, zip);
                zip.closeEntry();
            }
        }
        return new Transfer(serverBytes, output.count(), output.sha256());
    }

    static String leafName(String serverFile) {
        int separator = Math.max(serverFile.lastIndexOf('/'), serverFile.lastIndexOf('\\'));
        return serverFile.substring(separator + 1);
    }

    private static long readServerFile(Connection connection, String serverFile, OutputStream target) throws SQLException, IOException {
        String sql = "SELECT BulkColumn FROM OPENROWSET(BULK " + quoteLiteral(serverFile) + ", SINGLE_BLOB) AS backup_file";
        try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next())
                throw new IllegalStateException("BACKUP_FILE_MISSING: OPENROWSET returned no data for " + serverFile);

            try (InputStream stream = rows.getBinaryStream(1)) {
                if (stream == null)
                    throw new IllegalStateException("BACKUP_FILE_MISSING: OPENROWSET returned NULL for " + serverFile);

                return stream.transferTo(target);
            }
        } catch (SQLException exception) {
            throw switch (exception.getErrorCode()) {
                case BULK_PERMISSION_DENIED -> failure("BULK_READ_PERMISSION_DENIED", "the login lacks ADMINISTER BULK OPERATIONS permission", exception);
                case BULK_FILE_NOT_FOUND -> failure("BACKUP_FILE_MISSING", "SQL Server cannot read " + serverFile
                        + "; on SQL Server on Linux OPENROWSET(BULK) only works for sysadmin logins, on Windows the login needs ADMINISTER BULK OPERATIONS", exception);
                default -> failure("BACKUP_READ_FAILED", "OPENROWSET failed for " + serverFile, exception);
            };
        }
    }

    private static boolean deleteServerFiles(Connection connection, List<String> serverFiles) {
        boolean deleted = true;
        for (String serverFile : serverFiles) {
            try (Statement statement = connection.createStatement()) {
                drain(statement, statement.execute("EXEC master.dbo.xp_delete_file 0, " + quoteLiteral(serverFile)));
            } catch (SQLException exception) {
                // Best effort: the next run overwrites the file thanks to WITH INIT, FORMAT.
                deleted = false;
            }
        }
        return deleted;
    }

    /** Consumes every result set and update count so BACKUP's informational messages are fully received. */
    private static void drain(Statement statement, boolean isResultSet) throws SQLException {
        while (true) {
            if (isResultSet) {
                try (ResultSet ignored = statement.getResultSet()) {
                    // Nothing to read; the result only needs to be consumed.
                }
            } else if (statement.getUpdateCount() == -1) {
                return;
            }
            isResultSet = statement.getMoreResults();
        }
    }

    private static IllegalStateException failure(String code, String reason, SQLException exception) {
        String message = exception.getMessage() == null ? "" : ": " + exception.getMessage().replaceAll("[\\r\\n]+", " ").trim();
        return new IllegalStateException(code + ": " + reason + " (SQL Server error " + exception.getErrorCode() + ")" + message, exception);
    }

    /** BACKUP reports "BACKUP DATABASE successfully processed ..." as its last informational message. */
    private static String lastWarning(Statement statement) throws SQLException {
        String text = null;
        for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning())
            if (warning.getMessage() != null && !warning.getMessage().isBlank())
                text = warning.getMessage().replaceAll("[\\r\\n]+", " ").trim();

        return text;
    }

    private record BackupSet(long size, long sizeOnDisk, String finishedAt, String serverName, String serverVersion) { }
    private record Transfer(long serverBytes, long artifactBytes, String sha256) { }

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
