package app.alertify.grpc.discovery;

public final class WorkerReservation implements AutoCloseable {

    private final SelectedWorker worker;
    private final Runnable release;
    private boolean closed;

    WorkerReservation(SelectedWorker worker, Runnable release) {
        this.worker = worker;
        this.release = release;
    }

    public SelectedWorker worker() {
        return worker;
    }

    @Override
    public synchronized void close() {
        if (closed)
            return;
        closed = true;
        release.run();
    }
}
