package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.model.CyclicTargetCandidate;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.ExtendedToricClassKey;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JointAvoidanceTest {
    @Test void resumedIntermediateAndRootSearchesRunConcurrentlyWithoutLosingClasses(@TempDir Path directory) throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        try (var endpoints = new DiskStateSet(source.resolve("endpoint-residuals"));
             var intermediates = new DiskStateSet(source.resolve("intermediates"))) {
            endpoints.seal();
            for (var pair : pairs(20, 4).values()) intermediates.add(pair, 10);
            new ReconstructionCheckpoint(0, intermediates.seal()).write(source);
        }
        var intermediateBarrier = new CountDownLatch(2);
        var rootBarrier = new CountDownLatch(2);
        var middleCalls = new AtomicLong();
        var rootCalls = new AtomicLong();
        var result = JointAvoidance.resume(source, Files.createDirectory(directory.resolve("resumed")), pair -> {
            awaitParallel(intermediateBarrier);
            middleCalls.incrementAndGet();
            return false;
        }, pair -> {
            awaitParallel(rootBarrier);
            rootCalls.incrementAndGet();
            return true;
        });
        assertEquals(4, middleCalls.get());
        assertEquals(4, result.intermediates());
        assertEquals(4, result.propagatedIntermediates());
        assertTrue(result.roots() > 1);
        assertEquals(result.roots(), rootCalls.get());
        assertEquals(0, result.unresolved());
        assertTrue(Files.exists(source.resolve("intermediates/CURRENT")));
    }

    @Test void checkpointInventoryRejectsMissingOrMalformedCounts(@TempDir Path directory) throws Exception {
        assertThrows(IllegalStateException.class, () -> ReconstructionCheckpoint.read(directory));
        new ReconstructionCheckpoint(2, 3).write(directory);
        assertEquals(new ReconstructionCheckpoint(2, 3), ReconstructionCheckpoint.read(directory));
        Files.writeString(directory.resolve("reconstruction-checkpoint.txt"),
                "td20-reconstruction-checkpoint-v1\nendpoints=2\n");
        assertThrows(IllegalArgumentException.class, () -> ReconstructionCheckpoint.read(directory));
    }

    @Test void resumedSealedRootWaveSkipsIntermediateReconstruction(@TempDir Path directory) throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        long rootCount;
        try (var roots = new DiskStateSet(source.resolve("roots"))) {
            for (var pair : pairs(21, 4).values()) roots.add(pair, 11);
            rootCount = roots.seal();
        }
        var checkpoint = new RootReconstructionCheckpoint(2, 7, 4, rootCount);
        checkpoint.write(source);
        var rootCalls = new AtomicLong();
        var result = JointAvoidance.resume(source, Files.createDirectory(directory.resolve("resumed")),
                pair -> { fail("A root checkpoint must not repeat intermediate search"); return false; },
                pair -> { rootCalls.incrementAndGet(); return true; });
        assertEquals(checkpoint, RootReconstructionCheckpoint.read(source));
        assertEquals(rootCount, rootCalls.get());
        assertEquals(new JointAvoidance.Result(2, 7, 4, rootCount, 0), result);
    }

    @Test void rootCheckpointRejectsMalformedOrImpossibleCounts(@TempDir Path directory) throws Exception {
        assertThrows(IllegalStateException.class, () -> RootReconstructionCheckpoint.read(directory));
        assertThrows(IllegalArgumentException.class, () -> new RootReconstructionCheckpoint(1, 2, 3, 4));
        Files.writeString(directory.resolve("root-reconstruction-checkpoint.txt"),
                "td20-root-reconstruction-checkpoint-v1\nendpoints=2\nintermediates=3\nroots=4\n");
        assertThrows(IllegalArgumentException.class, () -> RootReconstructionCheckpoint.read(directory));
    }

    @Test void bulkCacheHitsAvoidAllIntermediateSearchAndRootReconstruction(@TempDir Path directory) throws Exception {
        Path source=Files.createDirectory(directory.resolve("source"));
        try(var endpoints=new DiskStateSet(source.resolve("endpoint-residuals"));
            var intermediates=new DiskStateSet(source.resolve("intermediates"))) {
            endpoints.seal();
            for(var pair:pairs(20,4).values())intermediates.add(pair,10);
            new ReconstructionCheckpoint(0,intermediates.seal()).write(source);
        }
        var lookedUp=new AtomicLong();
        var result=JointAvoidance.resume(source,Files.createDirectory(directory.resolve("resumed")),
                pair->{fail("Cached intermediates must not be searched");return false;},
                pair->{fail("Cached intermediates must not be reconstructed");return false;},batch->{
                    var hits=new boolean[batch.size()];Arrays.fill(hits,true);
                    for(var state:batch){assertEquals(20,state.pair().getBeta().size());assertEquals(10,state.maximumMoves());}
                    lookedUp.addAndGet(batch.size());return hits;
                });
        assertEquals(4,lookedUp.get());assertEquals(4,result.intermediates());
        assertEquals(0,result.propagatedIntermediates());assertEquals(0,result.roots());assertEquals(0,result.unresolved());
    }

    @Test void completeSupersededCheckpointsCannotResume(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("reconstruction-checkpoint.txt"),
                "td20-reconstruction-checkpoint-v1\nendpoints=0\nintermediates=0\n");
        assertThrows(IllegalArgumentException.class, () -> ReconstructionCheckpoint.read(directory));
        Files.writeString(directory.resolve("root-reconstruction-checkpoint.txt"),
                "td20-root-reconstruction-checkpoint-v1\nendpoints=0\nintermediates=0\n"
                        + "propagatedIntermediates=0\nroots=0\n");
        assertThrows(IllegalArgumentException.class, () -> RootReconstructionCheckpoint.read(directory));
    }

    private static void awaitParallel(CountDownLatch barrier) {
        barrier.countDown();
        try { assertTrue(barrier.await(5, TimeUnit.SECONDS), "Reconstruction searches must overlap"); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }

    private static HashMap<ExtendedToricClassKey, CyclicTargetPair> pairs(int n, int count) {
        var result = new HashMap<ExtendedToricClassKey, CyclicTargetPair>();
        var random = new Random(123);
        while (result.size() < count) {
            int[] images = IntStream.range(0, n).toArray();
            for (int i = n - 1; i > 0; i--) {
                int j = random.nextInt(i + 1), value = images[i];
                images[i] = images[j]; images[j] = value;
            }
            if (IntStream.range(0, n).anyMatch(x -> images[x] == x)) continue;
            var omega = new MulticyclePermutation();
            var visited = new boolean[n];
            for (int start = 0; start < n; start++) if (!visited[start]) {
                var cycle = new int[n]; int size = 0, x = start;
                do { visited[x] = true; cycle[size++] = x; x = images[x]; } while (x != start);
                omega.add(Cycle.of(Arrays.copyOf(cycle, size)));
            }
            new CyclicTargetCandidate(omega, Cycle.of(IntStream.range(0, n).toArray()))
                    .asCyclicTargetPair().ifPresent(pair -> result.put(pair.extendedToricClassKey(), pair));
        }
        return result;
    }
}
