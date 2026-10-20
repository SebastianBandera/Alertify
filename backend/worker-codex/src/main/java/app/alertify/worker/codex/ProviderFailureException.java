package app.alertify.worker.codex;

/** Closed diagnostic categories. Never include remote bodies or exception messages. */
final class ProviderFailureException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    enum Reason {
        TRANSPORT("The Codex HTTP transport failed"),
        HTTP("The Codex service returned an unsuccessful HTTP status"),
        STREAM_TRUNCATED("The Codex response stream ended without a completed response"),
        STREAM_FAILED("The Codex response stream reported a failure"),
        STREAM_INCOMPLETE("The Codex response stream reported an incomplete response"),
        STREAM_ERROR("The Codex response stream reported an error"),
        INVALID_STREAM("The Codex response stream contained an invalid event"),
        INVALID_RESPONSE("The Codex response did not contain a valid message or function call"),
        TOOL_RESULT_MISMATCH("The tool result did not match the pending call");

        private final String message;

        Reason(String message) { this.message = message; }
    }

    private final Reason reason;

    ProviderFailureException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    private ProviderFailureException(int status) {
        super(Reason.HTTP.message + " (HTTP " + status + ")");
        reason = Reason.HTTP;
    }

    static ProviderFailureException http(int status) { return new ProviderFailureException(status); }

    String code() { return "AI_PROVIDER_" + reason.name(); }
}
