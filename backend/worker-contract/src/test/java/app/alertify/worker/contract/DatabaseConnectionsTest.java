package app.alertify.worker.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class DatabaseConnectionsTest {

    @Test
    void buildsUrlsPerEngine() {
        assertThat(DatabaseConnections.jdbcUrl(credentials(DatabaseEngine.POSTGRESQL, null)))
                .isEqualTo("jdbc:postgresql://db.local:5432/alertify");
        assertThat(DatabaseConnections.jdbcUrl(credentials(DatabaseEngine.MARIADB, null)))
                .isEqualTo("jdbc:mariadb://db.local:5432/alertify");
        assertThat(DatabaseConnections.jdbcUrl(credentials(DatabaseEngine.SQL_SERVER, null)))
                .isEqualTo("jdbc:sqlserver://db.local:5432;databaseName=alertify");
        assertThat(DatabaseConnections.jdbcUrl(credentials(DatabaseEngine.ORACLE, null)))
                .isEqualTo("jdbc:oracle:thin:@//db.local:5432/alertify");
        assertThat(DatabaseConnections.jdbcUrl(credentials(DatabaseEngine.OTHER, "jdbc:h2:mem:test")))
                .isEqualTo("jdbc:h2:mem:test");
    }

    @Test
    void appendsOptionsWithTheEngineSeparatorWithoutDuplicatingIt() {
        assertThat(DatabaseConnections.jdbcUrl(credentials(DatabaseEngine.POSTGRESQL, "sslmode=require")))
                .isEqualTo("jdbc:postgresql://db.local:5432/alertify?sslmode=require");
        assertThat(DatabaseConnections.jdbcUrl(credentials(DatabaseEngine.POSTGRESQL, "?sslmode=require")))
                .isEqualTo("jdbc:postgresql://db.local:5432/alertify?sslmode=require");
        assertThat(DatabaseConnections.jdbcUrl(credentials(DatabaseEngine.SQL_SERVER, "encrypt=true;trustServerCertificate=true")))
                .isEqualTo("jdbc:sqlserver://db.local:5432;databaseName=alertify;encrypt=true;trustServerCertificate=true");
        assertThat(DatabaseConnections.jdbcUrl(credentials(DatabaseEngine.SQL_SERVER, ";encrypt=true")))
                .isEqualTo("jdbc:sqlserver://db.local:5432;databaseName=alertify;encrypt=true");
    }

    @Test
    void failsWithoutADriverInsteadOfTouchingTheNetwork() {
        DatabaseCredentials credentials = credentials(DatabaseEngine.OTHER, "jdbc:nonexistent-engine://db.local/alertify");

        assertThatThrownBy(() -> DatabaseConnections.open(credentials, Duration.ofSeconds(1)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("No suitable driver");
    }

    private static DatabaseCredentials credentials(DatabaseEngine engine, String options) {
        return new DatabaseCredentials(engine, "db.local", 5432, "alertify", "app", "pw", options);
    }
}
