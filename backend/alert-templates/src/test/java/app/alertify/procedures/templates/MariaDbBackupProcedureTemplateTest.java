package app.alertify.procedures.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.DatabaseEngine;

class MariaDbBackupProcedureTemplateTest {
    private static final DatabaseCredentials CREDENTIALS = new DatabaseCredentials(DatabaseEngine.MARIADB,
            "mariadb.example", 3306, "alertify", "backup", "se\"cr\\et", null);
    private static final Path DEFAULTS = Path.of("/tmp/alertify-mariadb.cnf");

    @Test
    void usesPortableUtf8mb4DumpWithConsistentSnapshotAndRoutines() {
        var command = MariaDbBackupProcedureTemplate.dumpCommand(CREDENTIALS, DEFAULTS, Map.of(), true, true);

        assertThat(command.getFirst()).isEqualTo("mariadb-dump");
        assertThat(command.get(1)).isEqualTo("--defaults-extra-file=" + DEFAULTS);
        assertThat(command).contains("--default-character-set=utf8mb4", "--hex-blob", "--quick", "--triggers",
                "--single-transaction", "--routines", "--events");
        assertThat(command).doesNotContain("--databases", "--password", CREDENTIALS.password());
        assertThat(command).containsSubsequence("--host", "mariadb.example", "--port", "3306", "--user", "backup", "alertify");
        assertThat(command.getLast()).isEqualTo("alertify");
    }

    @Test
    void omitsSnapshotAndRoutinesWhenDisabled() {
        var command = MariaDbBackupProcedureTemplate.dumpCommand(CREDENTIALS, DEFAULTS, Map.of(), false, false);

        assertThat(command).doesNotContain("--single-transaction", "--routines", "--events");
    }

    @Test
    void mapsJdbcOptionsToClientFlags() {
        var options = MariaDbBackupProcedureTemplate.options("sslMode=verify-full&connectTimeout=2500");
        var command = MariaDbBackupProcedureTemplate.dumpCommand(CREDENTIALS, DEFAULTS, options, true, true);

        assertThat(command).contains("--ssl", "--ssl-verify-server-cert", "--connect-timeout=3");

        var disabled = MariaDbBackupProcedureTemplate.dumpCommand(CREDENTIALS, DEFAULTS,
                MariaDbBackupProcedureTemplate.options("?sslMode=disable"), true, true);
        assertThat(disabled).contains("--skip-ssl").doesNotContain("--ssl");

        var trusted = MariaDbBackupProcedureTemplate.dumpCommand(CREDENTIALS, DEFAULTS,
                MariaDbBackupProcedureTemplate.options("sslMode=trust"), true, true);
        assertThat(trusted).contains("--ssl", "--skip-ssl-verify-server-cert");
    }

    @Test
    void rejectsUnsupportedOrMalformedOptions() {
        assertThatThrownBy(() -> MariaDbBackupProcedureTemplate.options("allowMultiQueries=true"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowMultiQueries");
        assertThatThrownBy(() -> MariaDbBackupProcedureTemplate.options("sslMode=required"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sslMode");
        assertThatThrownBy(() -> MariaDbBackupProcedureTemplate.options("connectTimeout=abc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("connectTimeout");
        assertThatThrownBy(() -> MariaDbBackupProcedureTemplate.options("sslMode=trust&sslMode=disable"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicated");
    }

    @Test
    void writesPasswordToOptionFileWithEscapedQuotes() {
        var defaults = MariaDbBackupProcedureTemplate.defaults(CREDENTIALS);

        assertThat(defaults).startsWith("[client]");
        assertThat(defaults).contains("password=\"se\\\"cr\\\\et\"");
    }
}
