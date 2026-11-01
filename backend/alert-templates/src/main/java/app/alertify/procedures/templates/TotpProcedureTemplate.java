package app.alertify.procedures.templates;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateTag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generates one RFC 6238 code for the current execution instant.
 *
 * <p>The worker compiles this file alone from its {@code sourcePath}, with no
 * classpath access to the rest of {@code alert-templates} (see
 * {@code AlertTemplateCompiler}), so the Base32 decoding below is
 * intentionally inlined rather than shared with {@code TotpBase32}, which
 * backs the TOTP QR wizard on the core application instead.
 */
@ProcedureTemplate(
    nameKey = "procedures.template.totp.name",
    descriptionKey = "procedures.template.totp.description",
    tags = @ProcedureTemplateTag(nameKey = "procedures.templateTag.security", color = "#7C3AED"),
    sourcePath = "app/alertify/procedures/templates/TotpProcedureTemplate.java",
    sensitiveResult = true
)
public final class TotpProcedureTemplate implements ProcedureEvaluator {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    @ProcedureParameter(
        labelKey = "procedures.template.totp.secret",
        descriptionKey = "procedures.template.totp.secretDescription",
        allowedSources = AlertParameterSource.SECRET,
        order = 1
    )
    private final String secret;

    @ProcedureParameter(
        labelKey = "procedures.template.totp.algorithm",
        descriptionKey = "procedures.template.totp.algorithmDescription",
        options = { "SHA1", "SHA256", "SHA512" },
        bindingAllowed = false,
        defaultValue = "SHA1",
        order = 2
    )
    private final String algorithm;

    @ProcedureParameter(
        labelKey = "procedures.template.totp.digits",
        descriptionKey = "procedures.template.totp.digitsDescription",
        options = { "6", "8" },
        bindingAllowed = false,
        defaultValue = "6",
        order = 3
    )
    private final Integer digits;

    @ProcedureParameter(
        labelKey = "procedures.template.totp.periodSeconds",
        descriptionKey = "procedures.template.totp.periodSecondsDescription",
        defaultValue = "30",
        order = 4
    )
    private final Integer periodSeconds;

    @ProcedureParameter(
        labelKey = "procedures.template.totp.minimumValiditySeconds",
        descriptionKey = "procedures.template.totp.minimumValiditySecondsDescription",
        defaultValue = "3",
        order = 5
    )
    private final Integer minimumValiditySeconds;

    public TotpProcedureTemplate(String secret, String algorithm, Integer digits, Integer periodSeconds, Integer minimumValiditySeconds) {
        this.secret = secret;
        this.algorithm = algorithm;
        this.digits = digits;
        this.periodSeconds = periodSeconds;
        this.minimumValiditySeconds = minimumValiditySeconds;
    }

    @Override
    public JsonNode execute(ProcedureExecutionContext context) throws Exception {
        return execute(context, Clock.systemUTC(), Thread::sleep);
    }

    JsonNode execute(ProcedureExecutionContext context, Clock clock, Sleeper sleeper) throws Exception {
        if (periodSeconds == null || periodSeconds <= 0)
            throw new IllegalArgumentException("periodSeconds must be positive");

        if (minimumValiditySeconds == null || minimumValiditySeconds < 0 || minimumValiditySeconds >= periodSeconds)
            throw new IllegalArgumentException("minimumValiditySeconds must be nonnegative and less than periodSeconds");

        if (digits == null || digits != 6 && digits != 8)
            throw new IllegalArgumentException("digits must be 6 or 8");

        String normalizedAlgorithm = algorithm == null ? "" : algorithm.toUpperCase(Locale.ROOT);
        if (!normalizedAlgorithm.equals("SHA1") && !normalizedAlgorithm.equals("SHA256")
                && !normalizedAlgorithm.equals("SHA512"))
            throw new IllegalArgumentException("algorithm must be SHA1, SHA256, or SHA512");

        Mac mac = Mac.getInstance("Hmac" + normalizedAlgorithm);
        mac.init(new SecretKeySpec(base32(secret), "Hmac" + normalizedAlgorithm));
        Instant now = minimumValiditySeconds == 0 ? context.now() : clock.instant();
        if (minimumValiditySeconds > 0) {
            Duration minimumValidity = Duration.ofSeconds(minimumValiditySeconds);
            while (true) {
                long start = Math.floorDiv(now.getEpochSecond(), periodSeconds) * (long) periodSeconds;
                Duration remaining = Duration.between(now, Instant.ofEpochSecond(start + periodSeconds));
                if (remaining.compareTo(minimumValidity) >= 0)
                    break;

                sleeper.sleep(remaining);
                now = clock.instant();
            }
        }
        long periodStart = Math.floorDiv(now.getEpochSecond(), periodSeconds) * (long) periodSeconds;
        long counter = Math.floorDiv(now.getEpochSecond(), periodSeconds);
        byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = (hash[offset] & 0x7f) << 24
                   | (hash[offset + 1] & 0xff) << 16
                   | (hash[offset + 2] & 0xff) << 8
                   |  hash[offset + 3] & 0xff;

        int modulus = digits == 8 ? 100_000_000 : 1_000_000;

        String code = String.format(Locale.ROOT, "%0" + digits + "d", binary % modulus);

        return JSON.createObjectNode()
                .put("code", code)
                .put("validFrom", Instant.ofEpochSecond(periodStart).toString())
                .put("validUntil", Instant.ofEpochSecond(periodStart + periodSeconds).toString());
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static byte[] base32(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("TOTP secret must not be blank");

        String normalized = value.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
        int padding = normalized.indexOf('=');
        if (padding >= 0) {
            for (int index = padding; index < normalized.length(); index++) {
                if (normalized.charAt(index) != '=')
                    throw new IllegalArgumentException("TOTP secret has invalid Base32 padding");
            }
            normalized = normalized.substring(0, padding);
        }
        byte[] result = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int output = 0;
        for (int index = 0; index < normalized.length(); index++) {
            int digit = BASE32.indexOf(normalized.charAt(index));
            if (digit < 0)
                throw new IllegalArgumentException("TOTP secret must be valid Base32");

            buffer = buffer << 5 | digit;
            bits += 5;
            if (bits >= 8) {
                result[output++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        if (output == 0)
            throw new IllegalArgumentException("TOTP secret is too short");

        return result;
    }
}
