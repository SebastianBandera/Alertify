package app.alertify.worker.runtime;

import app.alertify.worker.grpc.InvokePipeResponse;
import io.grpc.Deadline;

@FunctionalInterface
interface PipeInvoker {
    InvokePipeResponse invoke(String token, Deadline deadline);
}
