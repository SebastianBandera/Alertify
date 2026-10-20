package app.alertify.procedures.templates;

import java.util.Base64;

import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateTag;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Generates negative-test inputs; does not authenticate or verify the input JWT. */
@ProcedureTemplate(
    nameKey = "procedures.template.jwtNegativeTestTokens.name",
    descriptionKey = "procedures.template.jwtNegativeTestTokens.description",
    tags = @ProcedureTemplateTag(nameKey = "procedures.templateTag.security", color = "#7C3AED"),
    sourcePath = "app/alertify/procedures/templates/JwtNegativeTestTokensProcedureTemplate.java",
    sensitiveResult = true
)
public final class JwtNegativeTestTokensProcedureTemplate implements ProcedureEvaluator {
    private static final int MAX_JWT_CHARACTERS = 1024 * 1024;
    private static final String TAMPERED_CLAIM = "alertifyJwtTampered";
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    @ProcedureParameter(
        labelKey = "procedures.template.jwtNegativeTestTokens.jwt",
        descriptionKey = "procedures.template.jwtNegativeTestTokens.jwtDescription",
        allowedSecretValueTypes = "STRING",
        order = 1
    )
    private final String jwt;

    public JwtNegativeTestTokensProcedureTemplate(String jwt) {
        this.jwt = jwt;
    }

    @Override
    public JsonNode execute(ProcedureExecutionContext context) {
        try {
            if (jwt == null || jwt.isBlank() || jwt.length() > MAX_JWT_CHARACTERS)
                throw new IllegalArgumentException();

            String[] segments = jwt.split("\\.", -1);
            if (segments.length != 3)
                throw new IllegalArgumentException();

            ObjectNode header = object(decode(segments[0]));
            ObjectNode payload = object(decode(segments[1]));
            byte[] signature = decode(segments[2]);
            JsonNode algorithm = header.path("alg");
            if (!algorithm.isString() || algorithm.stringValue().isBlank() || algorithm.stringValue().equalsIgnoreCase("none")
                    || header.has("b64") && !isTrue(header.path("b64")))
                throw new IllegalArgumentException();

            // Change bytes, not Base64URL padding bits, while preserving signature length.
            signature[signature.length - 1] ^= 1;
            String invalidSignature = segments[0] + "." + segments[1] + "." + encode(signature);
            header.put("alg", "none");
            String unsigned = encode(JSON.writeValueAsBytes(header)) + "." + segments[1] + ".";
            payload.put(TAMPERED_CLAIM, !isTrue(payload.path(TAMPERED_CLAIM)));
            String tamperedPayload = segments[0] + "." + encode(JSON.writeValueAsBytes(payload)) + "." + segments[2];
            return JSON.createObjectNode()
                    .put("unsignedToken", unsigned)
                    .put("invalidSignatureToken", invalidSignature)
                    .put("tamperedPayloadToken", tamperedPayload);
        } catch (RuntimeException exception) {
            // Decoder and JSON exceptions may embed the input. Do not retain their causes.
            throw new IllegalArgumentException("jwt must be a signed compact JWT with Base64URL-encoded JSON header and claims, at most 1 MiB");
        }
    }

    private static ObjectNode object(byte[] value) {
        JsonNode node = JSON.readTree(value);
        if (!(node instanceof ObjectNode object))
            throw new IllegalArgumentException();

        return object;
    }

    private static byte[] decode(String value) {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException();

        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!encode(decoded).equals(value))
            throw new IllegalArgumentException();

        return decoded;
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static boolean isTrue(JsonNode value) {
        return value.isBoolean() && value.booleanValue();
    }
}
