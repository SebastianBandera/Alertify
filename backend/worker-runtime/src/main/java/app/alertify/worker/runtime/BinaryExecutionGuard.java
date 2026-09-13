package app.alertify.worker.runtime;

import java.util.concurrent.Semaphore;

import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.ExecuteProcedureRequest;

/**
 * Limits root executions carrying binary values to one per worker instance.
 * Nested procedures deliberately reuse the capacity already owned by their
 * parent execution so a callback routed to the same worker cannot deadlock.
 */
final class BinaryExecutionGuard {
    private final Semaphore permit = new Semaphore(1, true);

    Lease acquire(ExecuteAlertRequest request) throws InterruptedException {
        return acquire(hasBinary(request));
    }

    Lease acquire(ExecuteProcedureRequest request) throws InterruptedException {
        boolean root = request.getParentExecutionId().isBlank();
        return acquire(root && hasBinary(request));
    }

    private Lease acquire(boolean required) throws InterruptedException {
        if (!required)
            return () -> { };

        permit.acquire();
        return permit::release;
    }

    private static boolean hasBinary(ExecuteAlertRequest request) {
        return request.getParametersList().stream().anyMatch(parameter -> !parameter.getBinaryValue().isEmpty());
    }

    private static boolean hasBinary(ExecuteProcedureRequest request) {
        return request.getParametersList().stream().anyMatch(parameter -> !parameter.getBinaryValue().isEmpty());
    }

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
