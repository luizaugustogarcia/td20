package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiskStateSetTest {
    @Test void deduplicatesClassAndBudgetBeforeSealedScan(@TempDir Path directory) {
        var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
        try (var states = new DiskStateSet(directory.resolve("states"))) {
            states.add(pair,8); states.add(pair.canonicalRepresentative(),8); states.add(pair,9);
            assertEquals(2,states.seal());
            assertThrows(IllegalStateException.class, () -> states.add(pair,10));
            var result = new ArrayList<DiskStateSet.State>(); states.forEach(result::add);
            assertEquals(2,result.size()); assertEquals(8,result.getFirst().maximumMoves());
            assertEquals(9,result.getLast().maximumMoves());
            assertEquals(pair.extendedToricClassKey(),result.getFirst().pair().extendedToricClassKey());
        }
        assertThrows(IllegalStateException.class, () -> new DiskStateSet(directory.resolve("states")));
    }

    @Test void reopenedCheckpointsRejectMissingRecordsAndStayReadOnly(@TempDir Path directory) {
        var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
        Path path = directory.resolve("states");
        try (var states = new DiskStateSet(path)) {
            states.add(pair, 8);
            states.add(pair, 9);
            assertEquals(2, states.seal());
        }
        assertThrows(IllegalStateException.class, () -> DiskStateSet.openSealed(path, 3));
        assertThrows(IllegalStateException.class, () -> DiskStateSet.openSealed(directory.resolve("missing"), 0));
        try (var states = DiskStateSet.openSealed(path, 2)) {
            assertThrows(IllegalStateException.class, () -> states.add(pair, 10));
            var count = new java.util.concurrent.atomic.AtomicInteger();
            states.forEachConcurrently(2, state -> count.incrementAndGet());
            assertEquals(2, count.get());
            assertThrows(IllegalStateException.class, () -> states.forEachConcurrently(2, state -> {
                throw new IllegalArgumentException("search failed");
            }));
        }
    }

    @Test void bulkLookupSkipsHitsAndKeepsCrossBufferDuplicatesAndThePartialBatch(@TempDir Path directory) {
        var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
        String oldBatch = System.getProperty("td20.certificateLookupBatchSize");
        String oldBuffer = System.getProperty("td20.reconstructionBufferKeys");
        System.setProperty("td20.certificateLookupBatchSize","3");
        System.setProperty("td20.reconstructionBufferKeys","2");
        try (var states = new DiskStateSet(directory.resolve("states"))) {
            for (int budget=1;budget<=7;budget++) states.add(pair,budget);
            for (int budget=1;budget<=7;budget++) states.add(pair,budget);
            assertEquals(7,states.seal());
            var sizes = new ArrayList<Integer>();
            var hits = new java.util.concurrent.atomic.AtomicInteger();
            var misses = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
            states.forEachConcurrently(2,batch->{
                sizes.add(batch.size());var found=new boolean[batch.size()];
                for(int i=0;i<found.length;i++)found[i]=batch.get(i).maximumMoves()%2==0;
                return found;
            },state->hits.incrementAndGet(),state->{
                assertEquals(1,state.maximumMoves()%2);
                assertTrue(misses.add(state.maximumMoves()),"A duplicate reached sorting");
            });
            assertEquals(java.util.List.of(3,3,1),sizes);
            assertEquals(3,hits.get());assertEquals(java.util.Set.of(1,3,5,7),misses);
            assertThrows(IllegalStateException.class,()->states.forEachConcurrently(2,
                    batch->new boolean[batch.size()-1],state->fail("Incomplete lookup cannot skip work"),
                    state->fail("Incomplete lookup cannot dispatch work")));
        } finally {
            if(oldBatch==null)System.clearProperty("td20.certificateLookupBatchSize");else System.setProperty("td20.certificateLookupBatchSize",oldBatch);
            if(oldBuffer==null)System.clearProperty("td20.reconstructionBufferKeys");else System.setProperty("td20.reconstructionBufferKeys",oldBuffer);
        }
    }
}
