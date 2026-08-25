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
 * Resolves every IP currently published by the standard worker's shared DNS
 * name, matching the multi-address behavior of a Kubernetes headless service.
 */
@Component
public class WorkerStandardDnsResolver {

    public Set<WorkerStandardEndpoint> resolve(String host, int port) throws UnknownHostException {
        return Arrays.stream(InetAddress.getAllByName(host))
                .map(address -> new WorkerStandardEndpoint(address.getHostAddress(), port))
                .sorted(Comparator.comparing(WorkerStandardEndpoint::ipAddress))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
