package app.alertify.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import app.alertify.worker.ai.grpc.ChatClientMessage;
import io.grpc.stub.StreamObserver;

class ChatHandleConcurrencyTest {

    @Test
    void waitsForInFlightSendAndCompletesOnlyOnce() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger messages = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        StreamObserver<ChatClientMessage> observer = new StreamObserver<>() {
            @Override
            public void onNext(ChatClientMessage message) {
                messages.incrementAndGet();
                sending.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }

            @Override
            public void onCompleted() {
                assertThat(release.getCount()).isZero();
                completions.incrementAndGet();
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }
        };
        var handle = handle(observer, cleanups::incrementAndGet);
        FutureTask<Void> send = new FutureTask<>(() -> { handle.send(ChatClientMessage.getDefaultInstance()); return null; });
        Thread.ofPlatform().daemon().start(send);
        assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
        FutureTask<Void> close = new FutureTask<>(() -> { handle.close(); return null; });
        Thread closer = Thread.ofPlatform().daemon().start(close);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (closer.getState() != Thread.State.BLOCKED && !close.isDone() && System.nanoTime() < deadline)
                Thread.onSpinWait();

            assertThat(closer.getState()).isEqualTo(Thread.State.BLOCKED);
            assertThat(completions.get()).isZero();
        } finally {
            release.countDown();
        }
        send.get(5, TimeUnit.SECONDS);
        close.get(5, TimeUnit.SECONDS);
        handle.close();
        handle.send(ChatClientMessage.getDefaultInstance());
        assertThat(messages.get()).isEqualTo(1);
        assertThat(completions.get()).isEqualTo(1);
        assertThat(cleanups.get()).isEqualTo(1);
    }

    @Test
    void cleansUpOnceEvenIfCompletionThrows() throws Exception {
        AtomicInteger cleanups = new AtomicInteger();
        StreamObserver<ChatClientMessage> observer = new StreamObserver<>() {
            @Override public void onNext(ChatClientMessage message) { throw new AssertionError("Send after close"); }
            @Override public void onError(Throwable throwable) { throw new AssertionError(throwable); }
            @Override public void onCompleted() { throw new IllegalStateException("Completion failed"); }
        };
        var handle = handle(observer, cleanups::incrementAndGet);
        assertThatThrownBy(handle::close).isInstanceOf(IllegalStateException.class);
        handle.close();
        handle.send(ChatClientMessage.getDefaultInstance());
        assertThat(cleanups.get()).isEqualTo(1);
    }

    private static AiWorkerClient.ChatHandle handle(StreamObserver<ChatClientMessage> observer, Runnable cleanup) throws Exception {
        var constructor = AiWorkerClient.ChatHandle.class.getDeclaredConstructor(StreamObserver.class, Runnable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(observer, cleanup);
    }
}
