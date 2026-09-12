package app.alertify.grpc.api;

import java.time.Instant;
import java.util.List;

/**
 * One completed-minute sample for each minute in the half-open interval
 * {@code [from, to)}. The current, still incomplete minute is intentionally
 * excluded because only completed executions are persisted.
 */
public record WorkerActivityHistoryResponse(
        Instant from,
        Instant to,
        List<WorkerActivitySeriesResponse> series
) {
}
