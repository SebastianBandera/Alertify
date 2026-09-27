package app.alertify.procedures.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SqlServerNativeBackupProcedureTemplateTest {

    @Test
    void deletesServerFileAsBoundDataAndDrainsResults() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        String serverFile = "D:\\Backups\\night'ly; --.bak";
        when(connection.prepareStatement("EXEC master.dbo.xp_delete_file 0, ?")).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(statement.getUpdateCount()).thenReturn(0, -1);

        Boolean deleted = ReflectionTestUtils.invokeMethod(SqlServerNativeBackupProcedureTemplate.class,
                "deleteServerFiles", connection, List.of(serverFile));

        assertThat(deleted).isTrue();
        verify(statement).setString(1, serverFile);
        verify(resultSet).close();
        verify(statement).close();
    }

    @Test
    void continuesDeletingOtherFilesAfterSqlFailure() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement failed = mock(PreparedStatement.class);
        PreparedStatement successful = mock(PreparedStatement.class);
        when(connection.prepareStatement("EXEC master.dbo.xp_delete_file 0, ?")).thenReturn(failed, successful);
        when(failed.execute()).thenThrow(new SQLException("Deletion denied"));
        when(successful.getUpdateCount()).thenReturn(-1);

        Boolean deleted = ReflectionTestUtils.invokeMethod(SqlServerNativeBackupProcedureTemplate.class,
                "deleteServerFiles", connection, List.of("/backup/first.bak", "/backup/second.bak"));

        assertThat(deleted).isFalse();
        verify(successful).setString(1, "/backup/second.bak");
        verify(successful).execute();
        verify(failed).close();
        verify(successful).close();
    }

    @Test
    void buildsNativeBackupStatementWithRequestedOptions() {
        var sql = SqlServerNativeBackupProcedureTemplate.backupStatement("alert]ify", 1, true, true, true);

        assertThat(sql).startsWith("DECLARE @name NVARCHAR(128) = ?; BACKUP DATABASE [alert]]ify] TO DISK = ? WITH INIT, FORMAT, NAME = @name");
        assertThat(sql).endsWith(", COMPRESSION, CHECKSUM, COPY_ONLY");
    }

    @Test
    void omitsOptionalClausesAndStripesAcrossSeveralFiles() {
        var sql = SqlServerNativeBackupProcedureTemplate.backupStatement("alertify", 3, false, false, false);

        assertThat(sql).contains("TO DISK = ?, DISK = ?, DISK = ? WITH INIT, FORMAT, NAME = @name");
        assertThat(sql).doesNotContain("COMPRESSION", "CHECKSUM", "COPY_ONLY");
    }

    @Test
    void namesServerFilesInsideTheServerDirectory() {
        assertThat(SqlServerNativeBackupProcedureTemplate.serverFiles("/var/opt/mssql/data", "alertify", 1))
                .containsExactly("/var/opt/mssql/data/alertify.bak");
        assertThat(SqlServerNativeBackupProcedureTemplate.serverFiles("/var/opt/mssql/data/", "alertify", 2))
                .containsExactly("/var/opt/mssql/data/alertify.1.bak", "/var/opt/mssql/data/alertify.2.bak");
        assertThat(SqlServerNativeBackupProcedureTemplate.serverFiles("D:\\Backups", "alertify", 1))
                .containsExactly("D:\\Backups\\alertify.bak");
        assertThat(SqlServerNativeBackupProcedureTemplate.leafName("D:\\Backups\\alertify.1.bak")).isEqualTo("alertify.1.bak");
        assertThat(SqlServerNativeBackupProcedureTemplate.leafName("/var/opt/mssql/data/alertify.bak")).isEqualTo("alertify.bak");
    }

    @Test
    void usesDistinctServerFileNamesForRepeatedBackups() {
        String first = SqlServerNativeBackupProcedureTemplate.uniqueServerBaseName("nightly");
        String second = SqlServerNativeBackupProcedureTemplate.uniqueServerBaseName("nightly");

        assertThat(first).startsWith("nightly-").isNotEqualTo(second);
        assertThat(SqlServerNativeBackupProcedureTemplate.serverFiles("/var/opt/mssql/backup", first, 1))
                .containsExactly("/var/opt/mssql/backup/" + first + ".bak");
    }

    @Test
    void normalizesLogicalFileNames() {
        assertThat(SqlServerNativeBackupProcedureTemplate.baseName("nightly")).isEqualTo("nightly");
        assertThat(SqlServerNativeBackupProcedureTemplate.baseName(" nightly.bak ")).isEqualTo("nightly");
        assertThat(SqlServerNativeBackupProcedureTemplate.baseName("nightly.zip")).isEqualTo("nightly");
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.baseName("../nightly"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.baseName("night'ly"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.baseName(".bak"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafeServerDirectoriesAndQuotesLiterals() {
        assertThat(SqlServerNativeBackupProcedureTemplate.validateDirectory(" /var/opt/mssql/backup ")).isEqualTo("/var/opt/mssql/backup");
        assertThat(SqlServerNativeBackupProcedureTemplate.validateDirectory("D:\\Backups")).isEqualTo("D:\\Backups");
        assertThat(SqlServerNativeBackupProcedureTemplate.validateDirectory("\\\\sql-host\\share\\backups")).isEqualTo("\\\\sql-host\\share\\backups");
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.validateDirectory("relative/backups"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.validateDirectory("D:relative"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.validateDirectory("/var/opt/../etc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.validateDirectory("/var/opt/mssql'; DROP DATABASE x; --"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SqlServerNativeBackupProcedureTemplate.quoteLiteral("it's")).isEqualTo("N'it''s'");
        assertThat(SqlServerNativeBackupProcedureTemplate.quoteIdentifier("a]b")).isEqualTo("[a]]b]");
    }

    @Test
    void allowsCustomDirectoryOnlyForTheConfiguredServerAndExactPath() {
        String allowed = "sql.example.org:1433=/var/opt/mssql/backup;win-sql:1433=D:\\Backups";

        assertThat(SqlServerNativeBackupProcedureTemplate.allowedBackupDirectory("/var/opt/mssql/backup", "sql.example.org", 1433, allowed))
                .isEqualTo("/var/opt/mssql/backup");
        assertThat(SqlServerNativeBackupProcedureTemplate.allowedBackupDirectory("D:\\Backups", "WIN-SQL", 1433, allowed))
                .isEqualTo("D:\\Backups");
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.allowedBackupDirectory("/var/opt/mssql/backup", "other.example.org", 1433, allowed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.allowedBackupDirectory("/var/opt/mssql/backup-old", "sql.example.org", 1433, allowed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCustomDirectoryWithoutAValidWorkerAllowlist() {
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.allowedBackupDirectory("/var/opt/mssql/backup", "sql.example.org", 1433, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.allowedBackupDirectory("/var/opt/mssql/backup", "sql.example.org", 1433, "sql.example.org:1433=relative"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.allowedBackupDirectory("/var/opt/mssql/backup", "sql.example.org", 1433, "sql.example.org:1433="))
                .isInstanceOf(IllegalStateException.class);
    }
}
