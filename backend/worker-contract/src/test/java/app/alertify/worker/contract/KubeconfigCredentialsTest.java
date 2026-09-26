package app.alertify.worker.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class KubeconfigCredentialsTest {

    @Test
    void preservesExactUtf8TextAndRedactsToString() {
        String value = "apiVersion: v1\ncurrent-context: producción\n";
        KubeconfigCredentials credentials = new KubeconfigCredentials(value);

        assertEquals(value, credentials.kubeconfig());
        assertEquals("KubeconfigCredentials[REDACTED]", credentials.toString());
    }

    @Test
    void rejectsBlankNulMalformedUnicodeAndValuesOverOneMibOfUtf8() {
        assertThrows(IllegalArgumentException.class, () -> new KubeconfigCredentials(""));
        assertThrows(IllegalArgumentException.class, () -> new KubeconfigCredentials(" \n\t"));
        assertThrows(IllegalArgumentException.class, () -> new KubeconfigCredentials("a\0b"));
        assertThrows(IllegalArgumentException.class, () -> new KubeconfigCredentials("\uD800"));
        assertThrows(IllegalArgumentException.class, () -> new KubeconfigCredentials("x".repeat(KubeconfigCredentials.MAX_UTF8_BYTES + 1)));

        String exactMultibyteLimit = "é".repeat(KubeconfigCredentials.MAX_UTF8_BYTES / 2);
        assertEquals(exactMultibyteLimit, new KubeconfigCredentials(exactMultibyteLimit).kubeconfig());
        assertThrows(IllegalArgumentException.class, () -> new KubeconfigCredentials(exactMultibyteLimit + "a"));
    }
}
