package app.alertify.worker.standard.grpc;

import java.net.SocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.grpc.ForwardingServerCall;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;

/**
 * Temporarily logs incoming standard gRPC health checks and their responses so
 * developers can verify the connection between the backend and this worker.
 */
@Component
public class TemporaryGrpcHealthLoggingInterceptor implements ServerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TemporaryGrpcHealthLoggingInterceptor.class);
    private static final String HEALTH_CHECK_METHOD = HealthGrpc.getCheckMethod().getFullMethodName();

    @Override
    public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(ServerCall<RequestT, ResponseT> call, Metadata headers, ServerCallHandler<RequestT, ResponseT> next) {
        if (!HEALTH_CHECK_METHOD.equals(call.getMethodDescriptor().getFullMethodName()))
            return next.startCall(call, headers);

        SocketAddress remoteAddress = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        LOGGER.info("Received temporary gRPC health request from {}", remoteAddress);

        ServerCall<RequestT, ResponseT> loggingCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {

            @Override
            public void sendMessage(ResponseT message) {
                if (message instanceof HealthCheckResponse response) {
                    LOGGER.info("Sending temporary gRPC health response to {}: {}", remoteAddress, response.getStatus());
                }
                super.sendMessage(message);
            }
        };

        return next.startCall(loggingCall, headers);
    }
}
