package app.alertify.procedures.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import app.alertify.procedures.template.annotation.ProcedureTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JwtNegativeTestTokensProcedureTemplateTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HEADER = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"test-key\"}";
    private static final String PAYLOAD = "{\"sub\":\"test-user\",\"iss\":\"test-issuer\",\"aud\":\"test-audience\",\"exp\":2000000000,\"roles\":[\"reader\"]}";

    @Test
    void preservesTheUnmodifiedSegmentsAndChangesOnlyTheIntendedParts() {
        String token = token(HEADER, PAYLOAD);
        JsonNode result = execute(token);
        assertThat(result.propertyNames()).containsExactly("unsignedToken", "invalidSignatureToken", "tamperedPayloadToken");
        assertThat(JwtNegativeTestTokensProcedureTemplate.class.getAnnotation(ProcedureTemplate.class).sensitiveResult()).isTrue();

        String[] original = token.split("\\.", -1);
        String[] unsigned = result.path("unsignedToken").stringValue().split("\\.", -1);
        assertThat(unsigned).hasSize(3);
        assertThat(unsigned[1]).isEqualTo(original[1]);
        assertThat(unsigned[2]).isEmpty();
        var expectedHeader = (tools.jackson.databind.node.ObjectNode) JSON.readTree(HEADER);
        expectedHeader.put("alg", "none");
        assertThat(json(unsigned[0])).isEqualTo(expectedHeader);

        String[] invalid = result.path("invalidSignatureToken").stringValue().split("\\.", -1);
        assertThat(invalid[0]).isEqualTo(original[0]);
        assertThat(invalid[1]).isEqualTo(original[1]);
        assertThat(Base64.getUrlDecoder().decode(invalid[2])).containsExactly((byte) 1, (byte) 2, (byte) 2);

        String[] tampered = result.path("tamperedPayloadToken").stringValue().split("\\.", -1);
        assertThat(tampered[0]).isEqualTo(original[0]);
        assertThat(tampered[2]).isEqualTo(original[2]);
        assertThat(json(tampered[1]).path("alertifyJwtTampered").booleanValue()).isTrue();
        var claims = (tools.jackson.databind.node.ObjectNode) json(tampered[1]);
        claims.remove("alertifyJwtTampered");
        assertThat(claims).isEqualTo(JSON.readTree(PAYLOAD));
    }

    @ParameterizedTest
    @ValueSource(strings = { "true", "false", "null", "\"existing\"" })
    void alwaysChangesThePayloadEvenWhenTheMarkerAlreadyExists(String marker) {
        String payload = "{\"alertifyJwtTampered\":" + marker + "}";
        String[] tampered = execute(token(HEADER, payload)).path("tamperedPayloadToken").stringValue().split("\\.", -1);
        assertThat(json(tampered[1]).path("alertifyJwtTampered").booleanValue()).isEqualTo(!marker.equals("true"));
        assertThat(tampered[1]).isNotEqualTo(encode(payload));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "opaque-token", "a.b.c.d.e", "a.b.c", "e30.e30.AQID", "=.e30.AQID", "e30=.e30.AQID", "e30.e30.", "e30.e30.AR", "Bearer a.b.c", "a.b.c\r\n" })
    void rejectsMalformedInputWithoutExposingIt(String input) {
        assertThatThrownBy(() -> execute(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt must be a signed compact JWT with Base64URL-encoded JSON header and claims, at most 1 MiB")
                .hasNoCause();
    }

    @Test
    void rejectsInvalidHeaderClaimsUnencodedPayloadAndOversizedTokens() {
        for (String header : List.of("[]", "null", "{}", "{\"alg\":123}", "{\"alg\":\"none\"}", "{\"alg\":\" \"}", "{\"alg\":\"RS256\",\"b64\":false}", HEADER + " {}"))
            assertThatThrownBy(() -> execute(token(header, PAYLOAD))).isInstanceOf(IllegalArgumentException.class).hasNoCause();

        for (String payload : List.of("[]", "null", "123", "private-invalid-json", "{} {}"))
            assertThatThrownBy(() -> execute(token(HEADER, payload))).isInstanceOf(IllegalArgumentException.class).hasNoCause();

        assertThatThrownBy(() -> execute("x".repeat(1024 * 1024 + 1))).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test
    void validRsaSignatureFailsForBothSignedMutations() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        String input = encode(HEADER) + "." + encode(PAYLOAD);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        String original = input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        JsonNode result = execute(original);
        for (var entry : Map.of("original", original, "invalidSignatureToken", result.path("invalidSignatureToken").stringValue(),
                "tamperedPayloadToken", result.path("tamperedPayloadToken").stringValue()).entrySet()) {
            String[] segments = entry.getValue().split("\\.", -1);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keys.getPublic());
            verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
            boolean valid = verifier.verify(Base64.getUrlDecoder().decode(segments[2]));
            assertThat(valid).as("signature validity for %s", entry.getKey()).isEqualTo(entry.getKey().equals("original"));
        }
    }

    private static JsonNode execute(String token) {
        return new JwtNegativeTestTokensProcedureTemplate(token).execute(null);
    }

    private static String token(String header, String payload) {
        return encode(header) + "." + encode(payload) + ".AQID";
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode json(String encoded) {
        return JSON.readTree(Base64.getUrlDecoder().decode(encoded));
    }
}
