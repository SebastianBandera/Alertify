package app.alertify.worker.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class OidcTokenSetTest {

    @Test
    void roundTripsCanonicalJsonWithoutAssumingJwtTokens() {
        OidcTokenSet tokens = new OidcTokenSet(
                "opaque-access", "opaque-refresh", "header.payload.signature", "Bearer",
                Instant.parse("2026-09-18T15:30:00Z"), Instant.parse("2026-10-18T15:30:00Z"));

        String json = tokens.toJson();

        assertThat(json).isEqualTo("{\"accessToken\":\"opaque-access\",\"refreshToken\":\"opaque-refresh\","
                + "\"idToken\":\"header.payload.signature\",\"tokenType\":\"Bearer\","
                + "\"expiresAt\":\"2026-09-18T15:30:00Z\",\"refreshExpiresAt\":\"2026-10-18T15:30:00Z\"}");
        assertThat(OidcTokenSet.fromJson(json)).isEqualTo(tokens);
    }

    @Test
    void normalizesOptionalValuesAndTokenType() {
        OidcTokenSet tokens = OidcTokenSet.fromJson("{\"accessToken\":\"access\",\"refreshToken\":\"\","
                + "\"idToken\":null,\"tokenType\":\" Bearer \",\"expiresAt\":\"\",\"refreshExpiresAt\":null}");

        assertThat(tokens.refreshToken()).isNull();
        assertThat(tokens.idToken()).isNull();
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresAt()).isNull();
        assertThat(tokens.refreshExpiresAt()).isNull();
    }

    @Test
    void redactsEveryTokenFromToString() {
        OidcTokenSet tokens = new OidcTokenSet("access-value", "refresh-value", "id-value", "Bearer", null, null);

        assertThat(tokens.toString())
                .doesNotContain("access-value", "refresh-value", "id-value")
                .contains("accessToken=****", "refreshToken=****", "idToken=****");
    }

    @Test
    void rejectsMalformedTokenSets() {
        assertThatIllegalArgumentException().isThrownBy(() -> OidcTokenSet.fromJson("not json"));
        assertThatIllegalArgumentException().isThrownBy(() -> OidcTokenSet.fromJson("{\"tokenType\":\"Bearer\"}"))
                .withMessageContaining("accessToken");
        assertThatIllegalArgumentException().isThrownBy(() -> OidcTokenSet.fromJson(
                "{\"accessToken\":\"a\",\"tokenType\":\"Bearer\",\"expiresAt\":\"tomorrow\"}"))
                .withMessageContaining("expiresAt");
        assertThatIllegalArgumentException().isThrownBy(() -> OidcTokenSet.fromJson(
                "{\"accessToken\":\"a\",\"tokenType\":\"Bearer\",\"issuer\":\"https://issuer\"}"))
                .withMessageContaining("issuer");
    }
}
