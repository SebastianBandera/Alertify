package app.alertify.playwright;

import java.util.ArrayList;
import java.util.List;

import app.alertify.worker.contract.SecretValueSanitizer;
import tools.jackson.databind.json.JsonMapper;

/** ARIA names can contain escaped text inside an already decoded JSON string. */
final class BrowserObservationSanitizer {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BrowserObservationSanitizer() {
    }

    static String sanitize(String text, List<String> sensitive) {
        List<String> representations = new ArrayList<>();
        for (String value : sensitive) {
            if (value == null || value.isEmpty())
                continue;

            add(representations, value);
            // Accessible names normalize whitespace before ARIA serializes their text.
            String normalized = value.replaceAll("[\\s\\u00a0]+", " ").strip();
            if (!normalized.isEmpty())
                add(representations, normalized);
        }
        return SecretValueSanitizer.sanitize(text, representations);
    }

    private static void add(List<String> representations, String value) {
        representations.add(value);
        String quoted = JSON.writeValueAsString(value);
        representations.add(quoted.substring(1, quoted.length() - 1));
        representations.add(value.replace("'", "''"));
    }
}
