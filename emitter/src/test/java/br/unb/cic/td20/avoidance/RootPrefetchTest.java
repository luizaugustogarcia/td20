package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RootPrefetchTest {

    @Test
    void waitsForActivationAndDeduplicatesExactRootKeys() throws Exception {
        final byte[] first = key(1, 11), last = key(2, 11);
        assertFalse(Arrays.equals(first, last));

        val completed = new CountDownLatch(2);
        val calls = new AtomicInteger();

        try (val prefetch = new RootPrefetch(1,
                (pair, budget) -> {
                    assertEquals(21, pair.getBeta().size());
                    assertEquals(11, budget);
                    calls.incrementAndGet();
                    completed.countDown();

                    return false;
                },
                2,
                4)) {
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

    @Test
    void fullQueueDropsOptionalWorkWithoutBlockingProducer() throws Exception {
        val searched = new CountDownLatch(1);
        val calls = new AtomicInteger();

        try (val prefetch = new RootPrefetch(1,
                (pair, budget) -> {
                    calls.incrementAndGet();
                    searched.countDown();

                    return false;
                },
                1,
                4)) {
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

    @Test
    void finishJoinsInFlightSearchAndDiscardsQueuedRemainder() throws Exception {
        val entered = new CountDownLatch(1);
        val release = new CountDownLatch(1);
        val closing = new CountDownLatch(1);
        val finished = new CountDownLatch(1);
        val calls = new AtomicInteger();

        try (val prefetch = new RootPrefetch(1,
                (pair, budget) -> {
                    calls.incrementAndGet();
                    entered.countDown();

                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("Search not released");
                        }
                    } catch (final InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }

                    return false;
                },
                2,
                4);
            val closer = Executors.newSingleThreadExecutor()) {
            prefetch.publish(List.of(key(1, 11), key(2, 11)));
            prefetch.activate();
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            val close = closer.submit(() -> {
                closing.countDown();
                prefetch.finish();
                finished.countDown();
            });
            assertTrue(closing.await(10, TimeUnit.SECONDS));
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
            release.countDown();
            close.get(10, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void rejectsNonRootBudgetAndPropagatesFailure() {
        val calls = new AtomicInteger();
        val prefetch = new RootPrefetch(1, (pair, budget) -> {
            calls.incrementAndGet();
            return false;
        }, 1, 4);
        prefetch.publish(List.of(key(1, 9)));
        prefetch.activate();
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> {
                    for (;;) {
                        try {
                            prefetch.check();
                        } catch (final IllegalStateException error) {
                            break;
                        }

                        Thread.sleep(1);
                    }
                });

        val failure = assertThrows(IllegalStateException.class, prefetch::finish);
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(0, calls.get());
        assertThrows(IllegalStateException.class, () -> prefetch.publish(List.of(key(1, 11))));
    }

    @Test
    void bulkReplayFeedsOnlyMissesToIndependentGpuCallers() throws Exception {
        val searched = new CountDownLatch(1);
        val lookups = new AtomicInteger();
        val calls = new AtomicInteger();

        try (val prefetch = new RootPrefetch(2,
                (pair, budget) -> {
                    calls.incrementAndGet();
                    searched.countDown();

                    return true;
                },
                2,
                8,
                states -> {
                    lookups.incrementAndGet();
                    assertEquals(2, states.size());

                    return new boolean[] { true, false };
                })) {
            prefetch.publish(List.of(key(1, 11), key(2, 11)));
            prefetch.activate();
            assertTrue(searched.await(10, TimeUnit.SECONDS));
            prefetch.finish();
            assertEquals(1, lookups.get());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void batchedReplayFeedsMissesThroughCpuPrefixBeforeGpu() throws Exception {
        val gpuCalled = new CountDownLatch(1);
        val lookups = new AtomicInteger();
        val cpuCalls = new AtomicInteger();
        val gpuCalls = new AtomicInteger();
        val order = new java.util.concurrent.CopyOnWriteArrayList<String>();

        try (val prefetch = new RootPrefetch(1,
                1,
                (pair, budget) -> {
                    final int call = cpuCalls.incrementAndGet();
                    order.add("cpu-" + call);

                    return call == 1;
                },
                (pair, budget) -> {
                    gpuCalls.incrementAndGet();
                    order.add("gpu");
                    gpuCalled.countDown();

                    return true;
                },
                2,
                8,
                states -> {
                    lookups.incrementAndGet();
                    order.add("lookup");
                    assertEquals(2, states.size());

                    return new boolean[] { false, false };
                })) {
            prefetch.publish(List.of(key(1, 11), key(2, 11)));
            prefetch.activate();
            assertTrue(gpuCalled.await(10, TimeUnit.SECONDS));
            prefetch.finish();
            assertEquals(1, lookups.get(), "Certificate lookup must stay batched");
            assertEquals(2, cpuCalls.get(), "Every lookup miss enters CPU prefixes");
            assertEquals(1, gpuCalls.get(), "Only CPU-prefix misses enter the GPU");
            assertEquals(List.of("lookup", "cpu-1", "cpu-2", "gpu"), order);
        }
    }

    private static byte[] key(final int step, final int budget) {
        final int[] beta = new int[21], omega = new int[21];
        for (int i = 0; i < 21; i++) {
            beta[i] = i;
            omega[i] = i * (step == 2 ? 4 : step) % 21;
        }

        val pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(omega)), Cycle.of(beta));

        return DiskStateSet.encode(new DiskStateSet.State(pair, budget));
    }
}
