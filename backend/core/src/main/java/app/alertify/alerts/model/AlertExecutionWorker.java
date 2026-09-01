package app.alertify.alerts.model;

import java.util.Objects;
import java.util.UUID;

public record AlertExecutionWorker(
    String name,
    String ipAddress,
    int port,
    UUID instanceId
) {

    public AlertExecutionWorker {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name must not be blank");

        if (ipAddress == null || ipAddress.isBlank())
            throw new IllegalArgumentException("ipAddress must not be blank");
        
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("port must be between 1 and 65535");

        Objects.requireNonNull(instanceId, "instanceId must not be null");
    }
}
