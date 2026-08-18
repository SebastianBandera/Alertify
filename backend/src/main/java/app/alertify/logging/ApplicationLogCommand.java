package app.alertify.logging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

record ApplicationLogCommand(
    Instant eventAt,
    ApplicationLogLevel level,
    String source,
    String event,
    ApplicationLogOutcome outcome,
    LogActor actor,
    UUID requestId,
    String path,
    Map<String, ?> data
) {
}
