package app.alertify.worker.runtime;

import app.alertify.worker.grpc.InvokeProcedureResponse;
import io.grpc.Deadline;

@FunctionalInterface
interface ProcedureInvoker {
    InvokeProcedureResponse invoke(String token, Deadline deadline);
}
