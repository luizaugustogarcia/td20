package br.unb.cic.td20.verification;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;

import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckerStateSetTest {
    @Test
    void deduplicatesByClassAndKeepsBudgetsDistinct(@TempDir final Path directory) {
        final var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var first = new SearchState(ErikssonRoute.ENDPOINT_BUDGET, pair);
        try (final var states = new CheckerStateSet(directory.resolve("states"))) {
            states.add(first);
            states.add(new SearchState(ErikssonRoute.ENDPOINT_BUDGET, pair.canonicalRepresentative()));
            states.add(new SearchState(ErikssonRoute.ROOT_BUDGET, pair));
            assertEquals(2, states.seal().count());
            assertTrue(states.contains(first));
            final var recovered = new ArrayList<SearchState>();
            states.forEachBatch(recovered::addAll);
            assertEquals(2, recovered.size());
            assertEquals(pair.extendedToricClassKey(),
                    br.unb.cic.td20.compat.LegacyPairs.toLegacy(recovered.getFirst().pair()).extendedToricClassKey());
            assertThrows(IllegalStateException.class, () -> states.add(first));
        }
        assertThrows(IllegalStateException.class,
                () -> new CheckerStateSet(directory.resolve("states")));
    }

    @Test void concurrentBatchesDrainAndParallelReadsCoverEveryBudget(@TempDir Path directory) throws Exception {
        String[] properties = {"td20.reconstructionGeneratedBatchKeys", "td20.reconstructionWriters",
                "td20.reconstructionBufferKeys", "td20.reconstructionQueueBatches", "td20.reconstructionScanBatchKeys"};
        String[] previous = java.util.Arrays.stream(properties).map(System::getProperty).toArray(String[]::new);
        String[] values = {"17", "3", "128", "3", "3"};
        for (int i=0;i<properties.length;i++) System.setProperty(properties[i], values[i]);
        try (var states = new CheckerStateSet(directory.resolve("parallel"))) {
            var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)), Cycle.of(0,1,2));
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
                var jobs = new ArrayList<java.util.concurrent.Future<?>>();
                for (int worker=0;worker<4;worker++) jobs.add(pool.submit(() -> {
                    for (int i=0;i<103;i++) states.add(new SearchState(1+i%11,pair));
                }));
                for (var job:jobs) job.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals(11, states.seal().count());
            var seen = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
            states.forEachConcurrently(4, batch -> {
                boolean[] hits = new boolean[batch.size()];
                for(int i=0;i<hits.length;i++) hits[i]=batch.get(i).maximumMoves()%2==0;
                return hits;
            }, state -> assertTrue(seen.add(state.maximumMoves())),
               state -> assertTrue(seen.add(state.maximumMoves())));
            assertEquals(11,seen.size());
            assertThrows(IllegalStateException.class, () -> states.forEachConcurrently(4,
                    batch -> new boolean[0], state -> {}, state -> {}));
        } finally {
            for(int i=0;i<properties.length;i++)
                if(previous[i]==null) System.clearProperty(properties[i]); else System.setProperty(properties[i], previous[i]);
        }
    }

    @Test void smallInverseInventorySuppliesConcurrentCallbacks(@TempDir Path directory) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var states = new CheckerStateSet(directory.resolve("small"));
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
            for(int budget=1;budget<=11;budget++) states.add(new SearchState(budget,pair));
            assertEquals(11,states.seal().count());
            var completed = new java.util.concurrent.atomic.AtomicInteger();
            var run=executor.submit(() -> states.forEachConcurrently(2,state -> {
                entered.countDown();
                try { if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Callbacks stalled"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                completed.incrementAndGet();
            }));
            try { assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS),"Small inventories must use concurrent callbacks"); }
            finally { release.countDown(); }
            run.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(11,completed.get());
        } finally { release.countDown(); }
    }
}
