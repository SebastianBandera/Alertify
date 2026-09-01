package app.alertify.grpc.discovery;

import app.alertify.worker.grpc.WorkerStatusResponse;

public record SelectedWorker(
    WorkerEndpoint endpoint,
    WorkerStatusResponse status
) {

    public int currentLoad() {
        return status.getRunningCount() + status.getWaitingCount();
    }
}
