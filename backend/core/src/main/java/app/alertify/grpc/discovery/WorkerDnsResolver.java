package app.alertify.grpc.discovery;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Resolves every IP published by the common worker DNS name, matching a
 * Kubernetes headless service or a shared Docker Compose network alias.
 */
@Component
public class WorkerDnsResolver {

    public Set<WorkerEndpoint> resolve(String host, int port) throws UnknownHostException {
        return Arrays.stream(InetAddress.getAllByName(host))
                .map(address -> new WorkerEndpoint(address.getHostAddress(), port))
                .sorted(Comparator.comparing(WorkerEndpoint::ipAddress))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
