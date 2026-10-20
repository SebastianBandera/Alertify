package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ObfuscatedKeyMaterialTest {

    @Test
    void preservesComparisonCopyAndDestructionSemantics() {
        ObfuscatedKeyMaterial first = ObfuscatedKeyMaterial.from(new byte[] { 1, 2, 3 });
        ObfuscatedKeyMaterial same = first.copy();
        ObfuscatedKeyMaterial different = ObfuscatedKeyMaterial.from(new byte[] { 1, 2, 4 });
        ObfuscatedKeyMaterial shorter = ObfuscatedKeyMaterial.from(new byte[] { 1, 2 });
        try {
            assertThat(first.hasSameKey(first)).isTrue();
            assertThat(first.hasSameKey(same)).isTrue();
            assertThat(same.hasSameKey(first)).isTrue();
            assertThat(first.hasSameKey(different)).isFalse();
            assertThat(first.hasSameKey(shorter)).isFalse();
            var revealed = first.revealKey();
            try {
                assertThat(revealed.getEncoded()).containsExactly((byte) 1, (byte) 2, (byte) 3);
            } finally {
                ObfuscatedKeyMaterial.destroyRevealedKey(revealed);
            }
            first.destroy();
            assertThatThrownBy(first::revealKey).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(first::copy).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> first.hasSameKey(same)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> same.hasSameKey(first)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> first.hasSameKey(first)).isInstanceOf(IllegalStateException.class);
        } finally {
            first.destroy();
            same.destroy();
            different.destroy();
            shorter.destroy();
        }
    }

    @Test
    void reversedConcurrentComparisonsCompleteWithoutDeadlock() throws Exception {
        ObfuscatedKeyMaterial first = ObfuscatedKeyMaterial.from(new byte[32]);
        ObfuscatedKeyMaterial second = first.copy();
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<Void> forward = comparisons(first, second, start);
        FutureTask<Void> backward = comparisons(second, first, start);
        Thread.ofPlatform().daemon().start(forward);
        Thread.ofPlatform().daemon().start(backward);
        start.countDown();
        forward.get(10, TimeUnit.SECONDS);
        backward.get(10, TimeUnit.SECONDS);
        first.destroy();
        second.destroy();
    }

    private static FutureTask<Void> comparisons(ObfuscatedKeyMaterial first, ObfuscatedKeyMaterial second, CountDownLatch start) {
        return new FutureTask<>(() -> {
            start.await();
            for (int index = 0; index < 10_000; index++)
                assertThat(first.hasSameKey(second)).isTrue();

            return null;
        });
    }
}
