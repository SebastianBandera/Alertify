package app.alertify.worker.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class DatabaseCredentialsTest {

    @Test
    void roundTripsCanonicalJsonWithFixedKeyOrder() {
        DatabaseCredentials credentials = new DatabaseCredentials(
                DatabaseEngine.POSTGRESQL, " db.example.org ", 5432, "alertify", "app", "s3cret", " sslmode=require "
        );

        String json = credentials.toJson();

        assertThat(json).isEqualTo(
                "{\"engine\":\"POSTGRESQL\",\"host\":\"db.example.org\",\"port\":5432,\"database\":\"alertify\","
                        + "\"username\":\"app\",\"password\":\"s3cret\",\"options\":\"sslmode=require\"}"
        );
        assertThat(DatabaseCredentials.fromJson(json)).isEqualTo(credentials);
    }

    @Test
    void serialisesMissingOptionsAsNull() {
        DatabaseCredentials credentials = new DatabaseCredentials(
                DatabaseEngine.MARIADB, "localhost", 3306, "shop", "root", "pw", "   "
        );

        assertThat(credentials.options()).isNull();
        assertThat(credentials.toJson()).endsWith("\"options\":null}");
        assertThat(DatabaseCredentials.fromJson(credentials.toJson()).options()).isNull();
    }

    @Test
    void neverExposesThePasswordInToString() {
        DatabaseCredentials credentials = new DatabaseCredentials(
                DatabaseEngine.ORACLE, "oracle", 1521, "XEPDB1", "system", "top-secret", null
        );

        assertThat(credentials.toString()).doesNotContain("top-secret").contains("password=****");
    }

    @Test
    void rejectsInvalidPortsAndBlankFields() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatabaseCredentials(DatabaseEngine.POSTGRESQL, "h", 0, "d", "u", "p", null))
                .withMessageContaining("port");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatabaseCredentials(DatabaseEngine.POSTGRESQL, "h", 65_536, "d", "u", "p", null))
                .withMessageContaining("port");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatabaseCredentials(DatabaseEngine.POSTGRESQL, " ", 1, "d", "u", "p", null))
                .withMessageContaining("host");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatabaseCredentials(DatabaseEngine.POSTGRESQL, "h", 1, "d", "u", "", null))
                .withMessageContaining("password");
    }

    @Test
    void requiresAJdbcUrlInOptionsForOtherEngines() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatabaseCredentials(DatabaseEngine.OTHER, "h", 1, "d", "u", "p", null))
                .withMessageContaining("jdbc:");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatabaseCredentials(DatabaseEngine.OTHER, "h", 1, "d", "u", "p", "host=x"))
                .withMessageContaining("jdbc:");

        DatabaseCredentials other = new DatabaseCredentials(
                DatabaseEngine.OTHER, "h", 1, "d", "u", "p", "jdbc:h2:mem:test"
        );
        assertThat(other.options()).isEqualTo("jdbc:h2:mem:test");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DatabaseCredentials.fromJson("not json"))
                .withMessageContaining("valid JSON");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DatabaseCredentials.fromJson("\"a string\""))
                .withMessageContaining("JSON object");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DatabaseCredentials.fromJson(
                        "{\"engine\":\"POSTGRESQL\",\"host\":\"h\",\"port\":\"5432\",\"database\":\"d\",\"username\":\"u\",\"password\":\"p\"}"))
                .withMessageContaining("port");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DatabaseCredentials.fromJson(
                        "{\"engine\":\"SQLITE\",\"host\":\"h\",\"port\":1,\"database\":\"d\",\"username\":\"u\",\"password\":\"p\"}"))
                .withMessageContaining("engine");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DatabaseCredentials.fromJson(
                        "{\"engine\":\"POSTGRESQL\",\"host\":\"h\",\"port\":1,\"database\":\"d\",\"username\":\"u\",\"password\":\"p\",\"extra\":1}"))
                .withMessageContaining("extra");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DatabaseCredentials.fromJson(
                        "{\"engine\":\"POSTGRESQL\",\"host\":\"h\",\"port\":1,\"database\":\"d\",\"username\":\"u\"}"))
                .withMessageContaining("password");
    }
}
