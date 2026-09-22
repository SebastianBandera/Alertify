package app.alertify.procedures.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqlServerNativeBackupProcedureTemplateTest {

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
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.validateDirectory("/var/opt/../etc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlServerNativeBackupProcedureTemplate.validateDirectory("/var/opt/mssql'; DROP DATABASE x; --"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SqlServerNativeBackupProcedureTemplate.quoteLiteral("it's")).isEqualTo("N'it''s'");
        assertThat(SqlServerNativeBackupProcedureTemplate.quoteIdentifier("a]b")).isEqualTo("[a]]b]");
    }
}
