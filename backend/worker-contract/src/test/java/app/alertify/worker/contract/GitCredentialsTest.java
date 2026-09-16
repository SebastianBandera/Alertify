package app.alertify.worker.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class GitCredentialsTest {

    @Test
    void roundTripsCanonicalJsonWithFixedKeyOrder() {
        GitCredentials credentials = new GitCredentials(
                GitProvider.GITHUB, " github.example.org ", " app-bot ", "ghp_s3cret", " 2026-12-31 "
        );

        String json = credentials.toJson();

        assertThat(json).isEqualTo(
                "{\"provider\":\"GITHUB\",\"host\":\"github.example.org\",\"username\":\"app-bot\","
                        + "\"token\":\"ghp_s3cret\",\"tokenExpiresAt\":\"2026-12-31\"}"
        );
        assertThat(GitCredentials.fromJson(json)).isEqualTo(credentials);
    }

    @Test
    void serialisesMissingOptionalFieldsAsNull() {
        GitCredentials credentials = new GitCredentials(GitProvider.GITLAB, "gitlab.com", "   ", "tok", "   ");

        assertThat(credentials.username()).isNull();
        assertThat(credentials.tokenExpiresAt()).isNull();
        assertThat(credentials.toJson()).isEqualTo(
                "{\"provider\":\"GITLAB\",\"host\":\"gitlab.com\",\"username\":null,\"token\":\"tok\",\"tokenExpiresAt\":null}"
        );
        assertThat(GitCredentials.fromJson(credentials.toJson())).isEqualTo(credentials);
    }

    @Test
    void neverExposesTheTokenInToString() {
        GitCredentials credentials = new GitCredentials(GitProvider.BITBUCKET, "bitbucket.org", "app", "top-secret", null);

        assertThat(credentials.toString()).doesNotContain("top-secret").contains("token=****");
    }

    @Test
    void rejectsBlankHostOrEmptyToken() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GitCredentials(GitProvider.GITHUB, " ", "u", "t", null))
                .withMessageContaining("host");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GitCredentials(GitProvider.GITHUB, "h", "u", "", null))
                .withMessageContaining("token");
        assertThatNullPointerException()
                .isThrownBy(() -> new GitCredentials(null, "h", "u", "t", null))
                .withMessageContaining("provider");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GitCredentials.fromJson("not json"))
                .withMessageContaining("valid JSON");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GitCredentials.fromJson("\"a string\""))
                .withMessageContaining("JSON object");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GitCredentials.fromJson(
                        "{\"provider\":\"GITHUB\",\"host\":\"h\",\"token\":\"t\",\"extra\":1}"))
                .withMessageContaining("extra");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GitCredentials.fromJson(
                        "{\"provider\":\"GITHUB\",\"host\":\"h\"}"))
                .withMessageContaining("token");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GitCredentials.fromJson(
                        "{\"provider\":\"PERFORCE\",\"host\":\"h\",\"token\":\"t\"}"))
                .withMessageContaining("provider");
    }
}
