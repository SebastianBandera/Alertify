package app.alertify.worker.runtime;

import java.time.Instant;
import java.util.UUID;

final class WorkerInstanceIdentity {

    private final String id = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.now();

    String id() {
        return id;
    }

    Instant startedAt() {
        return startedAt;
    }
}
