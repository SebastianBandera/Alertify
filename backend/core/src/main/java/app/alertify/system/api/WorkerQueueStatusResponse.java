package app.alertify.system.api;

/** A worker whose queue has exceeded the administrative ticker threshold. */
public record WorkerQueueStatusResponse(String workerName, int waitingCount) {
}
