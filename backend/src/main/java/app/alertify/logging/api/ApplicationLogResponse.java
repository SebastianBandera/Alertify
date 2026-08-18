package app.alertify.logging.api;

import java.time.Instant;
import java.util.UUID;

import app.alertify.logging.ApplicationLogLevel;
import app.alertify.logging.ApplicationLogOutcome;
import tools.jackson.databind.JsonNode;

public record ApplicationLogResponse(
    Long id,
    Instant eventAt,
    ApplicationLogLevel level,
    String source,
    String event,
    ApplicationLogOutcome outcome,
    String userSubject,
    String username,
    UUID requestId,
    String path,
    JsonNode data
) {
}
