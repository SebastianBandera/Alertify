package app.alertify.grpc.discovery;

/**
 * Identifies one concrete standard worker replica returned by the shared DNS
 * name.
 */
public record WorkerStandardEndpoint(
    String ipAddress,
    int port
) {

    @Override
    public String toString() {
        return ipAddress + ":" + port;
    }
}
