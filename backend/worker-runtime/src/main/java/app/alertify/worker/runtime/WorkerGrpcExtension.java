package app.alertify.worker.runtime;

import io.grpc.BindableService;

/** Optional worker-specific gRPC service registered on the existing mTLS server. */
public interface WorkerGrpcExtension {
    BindableService grpcService();
}
