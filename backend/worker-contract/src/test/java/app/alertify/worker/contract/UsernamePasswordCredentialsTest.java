package app.alertify.worker.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class UsernamePasswordCredentialsTest {

    @Test
    void roundTripsCanonicalJsonWithoutExposingPassword() {
        UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(" user ", " p a s s ");

        assertThat(credentials.username()).isEqualTo("user");
        assertThat(credentials.password()).isEqualTo(" p a s s ");
        assertThat(credentials.toJson()).isEqualTo("{\"username\":\"user\",\"password\":\" p a s s \"}");
        assertThat(UsernamePasswordCredentials.fromJson(credentials.toJson())).isEqualTo(credentials);
        assertThat(credentials.toString()).doesNotContain("p a s s").contains("password=****");
    }

    @Test
    void normalizesMissingAndBlankUsernameToNull() {
        assertThat(UsernamePasswordCredentials.fromJson("{\"password\":\"secret\"}").username()).isNull();
        assertThat(UsernamePasswordCredentials.fromJson("{\"username\":\"  \",\"password\":\"secret\"}").toJson())
                .isEqualTo("{\"username\":null,\"password\":\"secret\"}");
    }

    @Test
    void rejectsInvalidShapes() {
        assertThatIllegalArgumentException().isThrownBy(() -> UsernamePasswordCredentials.fromJson("not json"));
        assertThatIllegalArgumentException().isThrownBy(() -> UsernamePasswordCredentials.fromJson("[]"));
        assertThatIllegalArgumentException().isThrownBy(() -> UsernamePasswordCredentials.fromJson("{\"username\":3,\"password\":\"secret\"}"));
        assertThatIllegalArgumentException().isThrownBy(() -> UsernamePasswordCredentials.fromJson("{\"password\":\"\"}"));
        assertThatIllegalArgumentException().isThrownBy(() -> UsernamePasswordCredentials.fromJson("{\"username\":null}"));
        assertThatIllegalArgumentException().isThrownBy(() -> UsernamePasswordCredentials.fromJson("{\"password\":\"secret\",\"extra\":true}"));
    }
}
