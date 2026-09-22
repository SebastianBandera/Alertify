package app.alertify.procedures.templates;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.DatabaseEngine;

class PostgresBackupProcedureTemplateTest {
    private static final DatabaseCredentials CREDENTIALS = new DatabaseCredentials(DatabaseEngine.POSTGRESQL,
            "postgres.example", 5432, "alertify", "backup", "secret", null);

    @Test
    void usesPortablePlainUtf8SqlWithCopyAndOptionalOwnershipMetadata() {
        var command = PostgresBackupProcedureTemplate.pgDumpCommand(CREDENTIALS, true, true);

        assertThat(command).contains("--format=plain", "--encoding=UTF8", "--no-owner", "--no-privileges");
        assertThat(command).doesNotContain("--inserts", "--column-inserts", "--attribute-inserts");
    }

    @Test
    void keepsPrivilegesAndOwnershipWhenTheirExclusionsAreDisabled() {
        var command = PostgresBackupProcedureTemplate.pgDumpCommand(CREDENTIALS, false, false);

        assertThat(command).doesNotContain("--no-owner", "--no-privileges");
        assertThat(command).containsSubsequence("--host", "postgres.example", "--port", "5432",
                "--username", "backup", "--dbname", "alertify");
    }
}
