package br.unb.cic.td20.avoidance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateSetWriterTest {
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Writer did not advance");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(error);
        }
    }

    @Test void boundedQueueOwnsSubmittedListsAndDrainsBeforeFinishing() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var persisted = new ArrayList<Integer>(); var flushed = new AtomicInteger();
        var writer = new StateSetWriter(1, "state-writer-test", batch -> {
            entered.countDown(); await(release);
            for (var key : batch) persisted.add((int) key[0]);
        }, flushed::incrementAndGet);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reused = new ArrayList<byte[]>(); reused.add(new byte[]{0});
            writer.submit(reused); await(entered);
            reused.clear(); reused.add(new byte[]{1}); writer.submit(reused); reused.clear();
            var third = executor.submit(() -> writer.submit(List.of(new byte[]{2})));
            try {
                assertThrows(TimeoutException.class, () -> third.get(100, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            third.get(10, TimeUnit.SECONDS);
            writer.finish();
            assertEquals(List.of(0, 1, 2), persisted);
            assertEquals(1, flushed.get());
            writer.finish(); assertEquals(1, flushed.get());
            assertThrows(IllegalStateException.class, () -> writer.submit(List.of(new byte[]{3})));
        } finally { release.countDown(); writer.finish(); }
    }

    @Test void writerFailureReleasesBlockedProducersAndPreventsSuccessfulFinish() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failure = new IllegalArgumentException("Persistence failed");
        var flushed = new AtomicInteger();
        var writer = new StateSetWriter(1, "state-writer-failure-test", batch -> {
            entered.countDown(); await(release); throw failure;
        }, flushed::incrementAndGet);
        try (var executor = Executors.newSingleThreadExecutor()) {
            writer.submit(List.of(new byte[]{0})); await(entered);
            writer.submit(List.of(new byte[]{1}));
            var blocked = executor.submit(() -> writer.submit(List.of(new byte[]{2})));
            try {
                assertThrows(TimeoutException.class, () -> blocked.get(100, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            var error = assertThrows(ExecutionException.class, () -> blocked.get(10, TimeUnit.SECONDS));
            assertSame(failure, error.getCause().getCause());
            assertSame(failure, assertThrows(IllegalStateException.class, writer::finish).getCause());
            assertEquals(0, flushed.get());
        } finally {
            release.countDown();
            assertThrows(IllegalStateException.class, writer::finish);
        }
    }

    @Test void finishJoinsTheFinalFlushBeforeResourcesCanClose() throws Exception {
        var flushing = new CountDownLatch(1); var release = new CountDownLatch(1);
        var writer = new StateSetWriter(1, "state-writer-flush-test", batch -> { }, () -> {
            flushing.countDown(); await(release);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            writer.submit(List.of(new byte[]{0}));
            var finishing = executor.submit(writer::finish); await(flushing);
            try {
                assertThrows(TimeoutException.class, () -> finishing.get(100, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            finishing.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); writer.finish(); }
    }

    @Test void failureInAnotherShardReleasesAProducerWaitingForQueueSpace() throws Exception {
        var sharedFailure = new AtomicReference<Throwable>();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failure = new IllegalArgumentException("Another shard failed");
        var writer = new StateSetWriter(1, "state-writer-other-shard-test", batch -> {
            entered.countDown(); await(release);
        }, () -> { }, sharedFailure);
        try (var executor = Executors.newSingleThreadExecutor()) {
            writer.submit(List.of(new byte[]{0})); await(entered);
            writer.submit(List.of(new byte[]{1}));
            var blocked = executor.submit(() -> writer.submit(List.of(new byte[]{2})));
            assertThrows(TimeoutException.class, () -> blocked.get(100, TimeUnit.MILLISECONDS));
            sharedFailure.set(failure);
            var error = assertThrows(ExecutionException.class, () -> blocked.get(10, TimeUnit.SECONDS));
            assertSame(failure, error.getCause().getCause());
        } finally {
            sharedFailure.compareAndSet(null, failure); release.countDown();
            assertThrows(IllegalStateException.class, writer::finish);
        }
    }

    @Test void allShardsCanStartFinalFlushBeforeAnyJoin() {
        var flushing=new CountDownLatch(2); var release=new CountDownLatch(1);
        var first=new StateSetWriter(1,"first-final-shard",batch -> {},() -> { flushing.countDown(); await(release); });
        var second=new StateSetWriter(1,"second-final-shard",batch -> {},() -> { flushing.countDown(); await(release); });
        try {
            first.requestFinish(); second.requestFinish(); await(flushing);
            assertFalse(first.finished()); assertFalse(second.finished());
        } finally { release.countDown(); first.finish(); second.finish(); }
    }
}
