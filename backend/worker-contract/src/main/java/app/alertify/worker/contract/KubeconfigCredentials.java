package app.alertify.worker.contract;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Structured, fully redacted value of a {@code KUBECONFIG} secret. */
public record KubeconfigCredentials(String kubeconfig) {

    public static final int MAX_UTF8_BYTES = 1024 * 1024;
    public KubeconfigCredentials {
        Objects.requireNonNull(kubeconfig, "kubeconfig must not be null");
        if (kubeconfig.isBlank())
            throw new IllegalArgumentException("kubeconfig must not be blank");

        if (kubeconfig.indexOf('\0') >= 0)
            throw new IllegalArgumentException("kubeconfig must not contain NUL characters");

        if (utf8(kubeconfig).remaining() > MAX_UTF8_BYTES)
            throw new IllegalArgumentException("kubeconfig must not exceed 1 MiB as UTF-8");
    }

    private static ByteBuffer utf8(String value) {
        try {
            return StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("kubeconfig must be valid UTF-8 text");
        }
    }

    @Override
    public String toString() {
        return "KubeconfigCredentials[REDACTED]";
    }
}
