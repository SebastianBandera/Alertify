package app.alertify.worker.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.playwright.WizardOperationDeadline;
import app.alertify.worker.runtime.WorkerExecutionTracker;
import app.alertify.worker.runtime.WorkerRuntimeProperties;
import app.alertify.worker.wizard.grpc.WizardEvent;
import app.alertify.worker.wizard.grpc.WizardRequest;
import io.grpc.stub.StreamObserver;

class PlaywrightWizardGrpcServiceTest {
    @Test
    void rejectsAnExpiredRequestBeforeAcquiringBrowserCapacity() throws Exception {
        var tracker = mock(WorkerExecutionTracker.class);
        try (var service = new PlaywrightWizardGrpcService(tracker, mock(WorkerRuntimeProperties.class))) {
            var events = new CopyOnWriteArrayList<WizardEvent>();
            var finished = new CountDownLatch(1);
            var requests = service.session(observer(events, finished));
            requests.onNext(WizardRequest.newBuilder().setOperation("OPEN").setRequestId("expired").setJson("{\"deadlineEpochMillis\":1}").build());
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events.stream().filter(event -> event.getType().equals("ERROR"))).hasSize(1);
            verifyNoInteractions(tracker);
        }
    }

    @Test
    void watchdogUsesTheInheritedDeadlineAndDiscardsLateResultsAfterClosing() throws Exception {
        var tracker = mock(WorkerExecutionTracker.class);
        try (var service = new PlaywrightWizardGrpcService(tracker, mock(WorkerRuntimeProperties.class))) {
            var events = new CopyOnWriteArrayList<WizardEvent>();
            var finished = new CountDownLatch(1);
            var requests = service.session(observer(events, finished));
            var nano = new AtomicLong();
            var budget = WizardOperationDeadline.fromEpochMillis(1001L, Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC), nano::get);
            ReflectionTestUtils.setField(requests, "operationStarted", System.nanoTime());
            ReflectionTestUtils.setField(requests, "operationDeadline", budget);
            nano.set(TimeUnit.MILLISECONDS.toNanos(1));
            ReflectionTestUtils.invokeMethod(requests, "check");
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            int count = events.size();
            ReflectionTestUtils.invokeMethod(requests, "emit", WizardEvent.newBuilder().setType("RESULT").setRequestId("late").setJson("{}").build());
            requests.onCompleted();
            assertThat(events).hasSize(count);
            verifyNoInteractions(tracker);
        }
    }

    private StreamObserver<WizardEvent> observer(CopyOnWriteArrayList<WizardEvent> events, CountDownLatch finished) {
        return new StreamObserver<>() {
            @Override public void onNext(WizardEvent event) { events.add(event); }
            @Override public void onError(Throwable error) { finished.countDown(); }
            @Override public void onCompleted() { finished.countDown(); }
        };
    }
}
