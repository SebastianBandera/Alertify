package app.alertify.playwright;

import java.util.List;

import app.alertify.worker.contract.SecretValueSanitizer;

/** ARIA names can contain escaped text inside an already decoded JSON string. */
final class BrowserObservationSanitizer {
    private BrowserObservationSanitizer() {
    }

    static String sanitize(String text, List<String> sensitive) {
        return SecretValueSanitizer.sanitize(text, sensitive);
    }
}
