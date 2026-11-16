package br.unb.cic.td20.avoidance;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionPipelineTest {
    @Test void gpuFinishesWhileInverseWorkIsBlockedAndCompletionWaitsForItsDrain(@TempDir Path directory) throws Exception {
        String oldWorkers = System.getProperty("td20.reconstructionInverseWorkers");
        System.setProperty("td20.reconstructionInverseWorkers", "1");
        var inverseStarted = new CountDownLatch(1); var releaseInverse = new CountDownLatch(1);
        var gpuDone = new CountDownLatch(8);
        var gpuDrained = new CountDownLatch(1);
        Set<Integer> residuals = ConcurrentHashMap.newKeySet();
        try (var states = states(directory); var executor = Executors.newSingleThreadExecutor()) {
            var run = executor.submit(() -> ReconstructionPipeline.process(states, directory.resolve("decoupled"), 2, 2,
                    null, (pair, budget) -> false, (pair, budget) -> {
                        gpuDone.countDown(); return budget % 2 == 0;
                    }, state -> {
                        inverseStarted.countDown();
                        try { assertTrue(releaseInverse.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException error) { throw new IllegalStateException(error); }
                        assertTrue(residuals.add(state.maximumMoves()));
                    }, gpuDrained::countDown));
            try {
                assertTrue(inverseStarted.await(5, TimeUnit.SECONDS));
                assertTrue(gpuDone.await(5, TimeUnit.SECONDS), "GPU dispatch must not wait for inverse generation");
                assertTrue(gpuDrained.await(5, TimeUnit.SECONDS), "Root prefetch starts before inverse work drains");
                assertFalse(run.isDone(), "Sealing must wait for every inverse obligation");
            } finally { releaseInverse.countDown(); }
            run.get(10, TimeUnit.SECONDS);
            assertEquals(Set.of(1, 3, 5, 7), residuals);
        } finally {
            releaseInverse.countDown();
            if (oldWorkers == null) System.clearProperty("td20.reconstructionInverseWorkers");
            else System.setProperty("td20.reconstructionInverseWorkers", oldWorkers);
        }
    }

    @Test void cpuFinishesWhileGpuIsBlockedAndEveryMissIsDrainedExactlyOnce(@TempDir Path directory) throws Exception {
        try (var states = states(directory); var executor = Executors.newSingleThreadExecutor()) {
            var cpuDone = new CountDownLatch(7);
            var gpuStarted = new CountDownLatch(1);
            var releaseGpu = new CountDownLatch(1);
            Set<Integer> cpuSeen = ConcurrentHashMap.newKeySet(), gpuSeen = ConcurrentHashMap.newKeySet();
            Set<Integer> residuals = ConcurrentHashMap.newKeySet();
            var completed = new AtomicInteger();
            var run = executor.submit(() -> ReconstructionPipeline.process(states, directory.resolve("queue"), 2, 1,
                    batch -> {
                        boolean[] hits = new boolean[batch.size()];
                        for (int i=0;i<hits.length;i++) hits[i] = batch.get(i).maximumMoves() == 2;
                        return hits;
                    }, (pair,budget) -> {
                        assertTrue(cpuSeen.add(budget)); cpuDone.countDown(); return budget < 5;
                    }, (pair,budget) -> {
                        gpuStarted.countDown();
                        try { assertTrue(releaseGpu.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { throw new IllegalStateException(e); }
                        assertTrue(gpuSeen.add(budget)); completed.incrementAndGet(); return budget % 2 == 0;
                    }, state -> assertTrue(residuals.add(state.maximumMoves()))));
            try {
                assertTrue(gpuStarted.await(5, TimeUnit.SECONDS));
                assertTrue(cpuDone.await(5, TimeUnit.SECONDS), "CPU work must finish with no GPU results");
                assertEquals(0, completed.get());
                assertFalse(run.isDone(), "Completion must wait for every GPU result");
            } finally { releaseGpu.countDown(); }
            run.get(10, TimeUnit.SECONDS);
            assertEquals(Set.of(1,3,4,5,6,7,8), cpuSeen);
            assertEquals(Set.of(5,6,7,8), gpuSeen);
            assertEquals(Set.of(5,7), residuals);
        }
    }

    @Test void cpuGpuAndLookupFailuresAbortWithoutDeadlock(@TempDir Path directory) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (var states = states(directory)) {
                assertThrows(IllegalStateException.class, () -> ReconstructionPipeline.process(states,
                        directory.resolve("cpu-failed"), 2, 2, null,
                        (pair,budget) -> { throw new IllegalArgumentException("CPU failed"); },
                        (pair,budget) -> true, state -> fail("No residuals expected")));
                assertThrows(IllegalStateException.class, () -> ReconstructionPipeline.process(states,
                        directory.resolve("gpu-failed"), 2, 2, null, (pair,budget) -> false,
                        (pair,budget) -> { throw new IllegalArgumentException("GPU failed"); },
                        state -> fail("No residuals expected")));
                assertThrows(IllegalStateException.class, () -> ReconstructionPipeline.process(states,
                        directory.resolve("lookup-failed"), 2, 2, batch -> new boolean[0],
                        (pair,budget) -> { fail("Incomplete lookup cannot dispatch"); return false; },
                        (pair,budget) -> true, state -> fail("No residuals expected")));
                assertThrows(IllegalStateException.class, () -> ReconstructionPipeline.process(states,
                        directory.resolve("inverse-failed"), 2, 2, null, (pair,budget) -> false,
                        (pair,budget) -> false, state -> { throw new IllegalArgumentException("Inverse failed"); }));
            }
        });
    }

    private static DiskStateSet states(Path directory) {
        var states = new DiskStateSet(directory.resolve("states"));
        var pair = ActiveErikssonAvoidanceTest.pair(7);
        for (int budget=1;budget<=8;budget++) states.add(pair,budget);
        assertEquals(8, states.seal());
        return states;
    }
}
