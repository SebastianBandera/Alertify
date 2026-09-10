package app.alertify.procedures.templates.totp;

import java.util.Locale;

/**
 * Decodes RFC 4648 Base32 TOTP secrets for the core application, e.g. to
 * validate a secret extracted from a QR code before it is stored. Not used
 * by {@code TotpProcedureTemplate} itself: the worker compiles that template
 * alone from its single source file, with no classpath access to this class,
 * so it keeps its own inlined copy of this logic.
 */
public final class TotpBase32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpBase32() {
    }

    public static byte[] decode(String value) {
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
            int digit = ALPHABET.indexOf(normalized.charAt(index));
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
