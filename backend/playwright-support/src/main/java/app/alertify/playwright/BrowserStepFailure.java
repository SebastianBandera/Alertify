package app.alertify.playwright;

import com.microsoft.playwright.TimeoutError;

/** Classifies browser diagnostics without exposing selectors, page text or values. */
final class BrowserStepFailure extends IllegalArgumentException {
    final int line;
    final String command;
    final String code;

    BrowserStepFailure(PlaywrightPageEngine.Command step, Exception cause) {
        super(message(step, cause));
        line = step.lineNumber();
        command = step.keyword();
        code = code(cause);
    }

    private static String code(Exception cause) {
        if (cause.getMessage() != null && cause.getMessage().contains("strict mode violation"))
            return "AMBIGUOUS_SELECTOR";

        return cause instanceof TimeoutError ? "ELEMENT_TIMEOUT" : "BROWSER_ACTION_FAILED";
    }

    private static String message(PlaywrightPageEngine.Command step, Exception cause) {
        String detail = switch (code(cause)) {
            case "AMBIGUOUS_SELECTOR" -> "more than one element matches; refine the preceding QUERY selector";
            case "ELEMENT_TIMEOUT" -> "the required element state was not reached before timeout";
            default -> "the browser action failed";
        };
        return "Line " + step.lineNumber() + " (" + step.keyword() + "): " + detail + ". Restart before replaying.";
    }
}
