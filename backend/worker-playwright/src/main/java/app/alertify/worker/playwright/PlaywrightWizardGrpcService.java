package app.alertify.worker.playwright;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;
import app.alertify.worker.runtime.WorkerExecutionTracker;
import app.alertify.worker.runtime.WorkerGrpcExtension;
import app.alertify.worker.runtime.WorkerRuntimeProperties;
import app.alertify.worker.wizard.grpc.PlaywrightWizardServiceGrpc;
import app.alertify.worker.wizard.grpc.WizardEvent;
import app.alertify.worker.wizard.grpc.WizardRequest;
import io.grpc.BindableService;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/** A child JVM makes cancellation independent of a blocked Playwright call. */
@Component
public class PlaywrightWizardGrpcService extends PlaywrightWizardServiceGrpc.PlaywrightWizardServiceImplBase implements WorkerGrpcExtension, AutoCloseable {
    private final WorkerExecutionTracker tracker;
    private final WorkerRuntimeProperties properties;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();

    public PlaywrightWizardGrpcService(WorkerExecutionTracker tracker, WorkerRuntimeProperties properties) {
        this.tracker = tracker;
        this.properties = properties;
        watchdog.scheduleWithFixedDelay(() -> sessions.forEach(Session::check), 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public BindableService grpcService() { return this; }

    @Override
    public StreamObserver<WizardRequest> session(StreamObserver<WizardEvent> observer) {
        Session session = new Session(observer);
        sessions.add(session);
        if (observer instanceof ServerCallStreamObserver<WizardEvent> server)
            server.setOnCancelHandler(session::close);

        return session;
    }

    @Override
    public void close() {
        sessions.forEach(Session::close);
        watchdog.shutdownNow();
    }

    private final class Session implements StreamObserver<WizardRequest>, AutoCloseable {
        private final StreamObserver<WizardEvent> observer;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Set<ProcessHandle> children = ConcurrentHashMap.newKeySet();
        private Process process;
        private OutputStream input;
        private AutoCloseable permit;
        private volatile long heartbeat = System.nanoTime();
        private volatile long operationStarted;
        private volatile String pendingId = "";

        private Session(StreamObserver<WizardEvent> observer) { this.observer = observer; }

        @Override
        public synchronized void onNext(WizardRequest request) {
            if (closed.get())
                return;

            heartbeat = System.nanoTime();
            if (request.getOperation().equals("HEARTBEAT"))
                return;

            if (request.getOperation().equals("CLOSE")) {
                close();
                return;
            }
            try {
                if (process == null) {
                    if (!request.getOperation().equals("OPEN"))
                        throw new IllegalStateException("Open a session first");

                    permit = tracker.tryBrowserSession(UUID.randomUUID().toString());
                    Path classpath = properties.compilerClasspathDirectory();
                    if (classpath == null)
                        throw new IllegalStateException("The compiler classpath is required");

                    process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-Xmx192m", "-cp", classpath.resolve("*").toString(), "app.alertify.playwright.InteractiveBrowserMain")
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    input = process.getOutputStream();
                    Thread.ofVirtual().name("playwright-wizard-output").start(this::read);
                }
                if (!pendingId.isEmpty() && request.getOperation().equals("PROCEDURE_REPLY")) {
                    request.writeDelimitedTo(input);
                    input.flush();
                    return;
                }

                if (!pendingId.isEmpty())
                    throw new IllegalStateException("Another browser operation is running");

                pendingId = request.getRequestId();
                operationStarted = System.nanoTime();
                request.writeDelimitedTo(input);
                input.flush();
            } catch (Exception exception) {
                emit(WizardEvent.newBuilder().setRequestId(request.getRequestId()).setType("ERROR")
                        .setJson("{\"error\":\"Browser unavailable, capacity full, or another operation is running\"}").build());
                if (process == null || !process.isAlive())
                    close();
            }
        }

        private void read() {
            try {
                WizardEvent event;
                while ((event = WizardEvent.parseDelimitedFrom(process.getInputStream())) != null) {
                    if ((event.getType().equals("RESULT") || event.getType().equals("ERROR") || event.getType().equals("IMAGE")) && event.getRequestId().equals(pendingId)) {
                        pendingId = "";
                        operationStarted = 0;
                    }
                    emit(event);
                }
            } catch (Exception ignored) {
                // Diagnostics from the browser are deliberately not exposed.
            } finally {
                close();
            }
        }

        private synchronized void emit(WizardEvent event) {
            if (closed.get())
                return;

            if (event.getType().equals("FRAME") && observer instanceof ServerCallStreamObserver<WizardEvent> server && !server.isReady())
                return;

            try { observer.onNext(event); } catch (RuntimeException ignored) { close(); }
        }

        private void check() {
            Process current = process;
            if (current != null)
                current.descendants().forEach(children::add);

            long now = System.nanoTime();
            if (now - heartbeat > TimeUnit.SECONDS.toNanos(30) || (operationStarted != 0 && now - operationStarted > TimeUnit.MINUTES.toNanos(3)))
                close();
        }

        @Override
        public void onError(Throwable failure) { close(); }
        @Override
        public void onCompleted() { close(); }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true))
                return;

            sessions.remove(this);
            Thread.ofVirtual().start(() -> {
                Process current = process;
                if (current != null) {
                    current.descendants().forEach(children::add);
                    try { current.getOutputStream().close(); } catch (Exception ignored) { }
                    try {
                        if (!current.waitFor(2, TimeUnit.SECONDS))
                            current.destroy();

                        if (!current.waitFor(2, TimeUnit.SECONDS))
                            current.destroyForcibly();

                        children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
                        current.waitFor(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        current.destroyForcibly();
                        children.forEach(ProcessHandle::destroyForcibly);
                    }
                }
                try { if (permit != null) permit.close(); } catch (Exception ignored) { }
                synchronized (this) {
                    try { observer.onNext(WizardEvent.newBuilder().setType("CLOSED").build()); observer.onCompleted(); } catch (RuntimeException ignored) { }
                }
            });
        }
    }
}
