package app.alertify.grpc.discovery;

/**
 * Identifies one concrete worker replica returned by the shared DNS name.
 */
public record WorkerEndpoint(
    String ipAddress,
    int port
) {

    @Override
    public String toString() {
        return ipAddress + ":" + port;
    }
}
