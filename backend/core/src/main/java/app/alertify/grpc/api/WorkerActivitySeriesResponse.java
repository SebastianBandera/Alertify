package app.alertify.grpc.api;

import java.util.List;
import java.util.UUID;

public record WorkerActivitySeriesResponse(
        UUID workerInstanceId,
        String workerName,
        String address,
        String kind,
        List<Integer> values
) {
}
