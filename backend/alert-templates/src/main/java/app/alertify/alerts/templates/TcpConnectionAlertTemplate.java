package app.alertify.alerts.templates;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;

/**
 * Standard alert that resolves a hostname and verifies that one of its
 * addresses accepts a TCP connection on the configured port.
 */
@AlertTemplate(
    nameKey = "alerts.template.tcpConnection.name",
    descriptionKey = "alerts.template.tcpConnection.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.network", color = "#0EA5E9"),
    sourcePath = "app/alertify/alerts/templates/TcpConnectionAlertTemplate.java"
)
public final class TcpConnectionAlertTemplate implements AlertEvaluator {

    private static final int MAX_TIMEOUT_SECONDS = 3_600;

    @AlertParameter(
        labelKey = "alerts.template.tcpConnection.host",
        descriptionKey = "alerts.template.tcpConnection.hostDescription",
        bindingAllowed = true,
        order = 1
    )
    private final String host;

    @AlertParameter(
        labelKey = "alerts.template.tcpConnection.port",
        descriptionKey = "alerts.template.tcpConnection.portDescription",
        bindingAllowed = true,
        order = 2
    )
    private final int port;

    @AlertParameter(
        labelKey = "alerts.template.tcpConnection.timeout",
        descriptionKey = "alerts.template.tcpConnection.timeoutDescription",
        options = { "1", "3", "5", "10", "30" },
        bindingAllowed = true,
        defaultValue = "3",
        order = 3
    )
    private final int timeoutSeconds;

    public TcpConnectionAlertTemplate(String host, int port, int timeoutSeconds) {
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("host must not be blank");

        if (port <= 0 || port > 65_535)
            throw new IllegalArgumentException("port must be between 1 and 65535");

        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS)
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);

        this.host = host.trim();
        this.port = port;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) {
        long totalStartedNanos = System.nanoTime();
        long dnsStartedNanos = System.nanoTime();
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            return warning(
                context, List.of(), elapsedMillis(dnsStartedNanos), 0,
                elapsedMillis(totalStartedNanos), "unknown_host", exception
            );
        }

        long dnsResolutionMs = elapsedMillis(dnsStartedNanos);
        List<String> resolvedAddresses = Arrays.stream(addresses)
            .map(InetAddress::getHostAddress)
            .distinct()
            .toList();
        if (addresses.length == 0) {
            return warning(
                context, resolvedAddresses, dnsResolutionMs, 0,
                elapsedMillis(totalStartedNanos), "no_address",
                new IOException("Hostname resolution returned no addresses")
            );
        }

        long connectStartedNanos = System.nanoTime();
        long timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long deadlineNanos = connectStartedNanos + timeoutNanos;
        IOException lastFailure = null;

        for (InetAddress address : addresses) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                lastFailure = new SocketTimeoutException("TCP connection timed out");
                break;
            }

            int remainingMillis = (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), remainingMillis);
                long connectMs = elapsedMillis(connectStartedNanos);
                long totalLatencyMs = elapsedMillis(totalStartedNanos);
                Map<String, Object> statusMessage = statusMessage(
                    resolvedAddresses, dnsResolutionMs, connectMs, totalLatencyMs, true
                );
                statusMessage.put("connectedAddress", address.getHostAddress());
                context.setState(state(true, address.getHostAddress(), totalLatencyMs, null));
                return AlertResult.success(statusMessage);
            } catch (IOException exception) {
                lastFailure = exception;
            }
        }

        long connectMs = elapsedMillis(connectStartedNanos);
        return warning(
            context, resolvedAddresses, dnsResolutionMs, connectMs,
            elapsedMillis(totalStartedNanos), failureReason(lastFailure), lastFailure
        );
    }

    private AlertResult warning(AlertExecutionContext context, List<String> resolvedAddresses, long dnsResolutionMs, long connectMs, long totalLatencyMs, String failureReason, IOException failure) {
        Map<String, Object> statusMessage = statusMessage(
            resolvedAddresses, dnsResolutionMs, connectMs, totalLatencyMs, false
        );
        statusMessage.put("failureReason", failureReason);
        if (failure != null && failure.getMessage() != null && !failure.getMessage().isBlank())
            statusMessage.put("failureMessage", failure.getMessage());

        context.setState(state(false, null, totalLatencyMs, failureReason));
        return AlertResult.warn(statusMessage);
    }

    private Map<String, Object> statusMessage(List<String> resolvedAddresses, long dnsResolutionMs, long connectMs, long totalLatencyMs, boolean connected) {
        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("host", host);
        statusMessage.put("port", port);
        statusMessage.put("timeoutSeconds", timeoutSeconds);
        statusMessage.put("hostnameResolved", !resolvedAddresses.isEmpty());
        statusMessage.put("resolvedAddresses", resolvedAddresses);
        statusMessage.put("portOpen", connected);
        statusMessage.put("reachable", connected);
        statusMessage.put("dnsResolutionMs", dnsResolutionMs);
        statusMessage.put("connectMs", connectMs);
        statusMessage.put("totalLatencyMs", totalLatencyMs);
        statusMessage.put("checkedAt", Instant.now().toString());
        return statusMessage;
    }

    private String state(boolean connected, String connectedAddress, long totalLatencyMs, String failureReason) {
        String value = "host=" + host + ";port=" + port + ";reachable=" + connected + ";totalLatencyMs=" + totalLatencyMs;
        if (connectedAddress != null)
            return value + ";connectedAddress=" + connectedAddress;

        return value + ";failureReason=" + failureReason;
    }

    private static String failureReason(IOException exception) {
        if (exception instanceof SocketTimeoutException)
            return "timeout";

        if (exception instanceof NoRouteToHostException)
            return "unreachable";

        if (exception instanceof ConnectException)
            return "connect_failed";

        return "io_error";
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, System.nanoTime() - startedNanos) / 1_000_000;
    }
}
