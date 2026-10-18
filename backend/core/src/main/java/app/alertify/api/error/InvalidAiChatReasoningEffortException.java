package app.alertify.api.error;

public class InvalidAiChatReasoningEffortException extends RuntimeException {
    public InvalidAiChatReasoningEffortException() { super("The requested reasoning effort is not allowed by the current system configuration"); }
}
