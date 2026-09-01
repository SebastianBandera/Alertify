package app.alertify.worker.runtime;

import java.util.UUID;

final class WorkerInstanceIdentity {

    private final String id = UUID.randomUUID().toString();

    String id() {
        return id;
    }
}
