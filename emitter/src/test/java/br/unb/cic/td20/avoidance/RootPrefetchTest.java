package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RootPrefetchTest {
    @Test void waitsForActivationAndDeduplicatesExactRootKeys() throws Exception {
        byte[] first = key(1, 11), last = key(2, 11);
        assertFalse(Arrays.equals(first, last));
        var completed = new CountDownLatch(2);
        var calls = new AtomicInteger();
        try (var prefetch = new RootPrefetch(1, (pair, budget) -> {
            assertEquals(21, pair.getBeta().size());
            assertEquals(11, budget);
            calls.incrementAndGet();
            completed.countDown();
            return false;
        }, 2, 4)) {
            prefetch.publish(List.of(first, first, last));
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS));
            assertEquals(0, calls.get());
            prefetch.activate();
            prefetch.activate();
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            prefetch.finish();
            assertEquals(2, calls.get());
        }
    }

    @Test void fullQueueDropsOptionalWorkWithoutBlockingProducer() throws Exception {
        var searched = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var prefetch = new RootPrefetch(1, (pair, budget) -> {
            calls.incrementAndGet(); searched.countDown(); return false;
        }, 1, 4)) {
            prefetch.publish(List.of(key(1, 11)));
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> prefetch.publish(List.of(key(2, 11))));
            prefetch.activate();
            assertTrue(searched.await(10, TimeUnit.SECONDS));
            prefetch.finish();
            assertEquals(1, calls.get());
            prefetch.publish(List.of(key(2, 11)));
            assertEquals(1, calls.get());
        }
    }

    @Test void finishJoinsInFlightSearchAndDiscardsQueuedRemainder() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closing = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var prefetch = new RootPrefetch(1, (pair, budget) -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Search not released"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            return false;
        }, 2, 4); var closer = Executors.newSingleThreadExecutor()) {
            prefetch.publish(List.of(key(1, 11), key(2, 11)));
            prefetch.activate();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var close = closer.submit(() -> {
                closing.countDown(); prefetch.finish(); finished.countDown();
            });
            assertTrue(closing.await(10, TimeUnit.SECONDS));
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
            release.countDown();
            close.get(10, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test void rejectsNonRootBudgetAndPropagatesFailure() {
        var calls = new AtomicInteger();
        var prefetch = new RootPrefetch(1, (pair, budget) -> { calls.incrementAndGet(); return false; }, 1, 4);
        prefetch.publish(List.of(key(1, 9)));
        prefetch.activate();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (;;) {
                try { prefetch.check(); }
                catch (IllegalStateException error) { break; }
                Thread.sleep(1);
            }
        });
        var failure = assertThrows(IllegalStateException.class, prefetch::finish);
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(0, calls.get());
        assertThrows(IllegalStateException.class, () -> prefetch.publish(List.of(key(1, 11))));
    }

    @Test void bulkReplayFeedsOnlyMissesToIndependentGpuCallers() throws Exception {
        var searched=new CountDownLatch(1);
        var lookups=new AtomicInteger(); var calls=new AtomicInteger();
        try (var prefetch=new RootPrefetch(2,(pair,budget) -> {
            calls.incrementAndGet(); searched.countDown(); return true;
        },2,8,states -> {
            lookups.incrementAndGet(); assertEquals(2,states.size());
            return new boolean[]{true,false};
        })) {
            prefetch.publish(List.of(key(1,11),key(2,11))); prefetch.activate();
            assertTrue(searched.await(10,TimeUnit.SECONDS)); prefetch.finish();
            assertEquals(1,lookups.get()); assertEquals(1,calls.get());
        }
    }

    private static byte[] key(int step, int budget) {
        int[] beta = new int[21], omega = new int[21];
        for (int i = 0; i < 21; i++) { beta[i] = i; omega[i] = i * (step == 2 ? 4 : step) % 21; }
        var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(omega)), Cycle.of(beta));
        return DiskStateSet.encode(new DiskStateSet.State(pair, budget));
    }
}
