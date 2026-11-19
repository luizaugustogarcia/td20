package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiskStateSetTest {
    @Test void sealAndCloseRejectActiveGenerationWithoutOmittingItsOutput(@TempDir Path directory) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pair = ActiveErikssonAvoidanceTest.pair(3);
        try (var states = new DiskStateSet(directory.resolve("states"));
             var executor = Executors.newSingleThreadExecutor()) {
            var producer = executor.submit(() -> states.addGenerated(8, output -> {
                output.accept(pair); entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Producer did not resume");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(error);
                }
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, states::seal);
                assertThrows(IllegalStateException.class, states::countKeys);
                assertThrows(IllegalStateException.class, states::close);
            } finally { release.countDown(); }
            producer.get(10, TimeUnit.SECONDS);
            assertEquals(1, states.seal());
            assertEquals(List.of(HexFormat.of().formatHex(DiskStateSet.encode(new DiskStateSet.State(pair, 8)))), keys(states));
        } finally { release.countDown(); }
    }

    @Test void differentWriterLayoutsKeepExactlyTheSameKeys(@TempDir Path directory) {
        var parents = new ArrayList<CyclicTargetPair>();
        ErikssonInverseReconstruction.preimages(ActiveErikssonAvoidanceTest.pair(5), 1, parents::add);
        var expected = new java.util.TreeSet<String>();
        for (int budget = 8; budget <= 11; budget++) for (var pair : parents)
            expected.add(HexFormat.of().formatHex(DiskStateSet.encode(new DiskStateSet.State(pair, budget))));
        String oldWriters = System.getProperty("td20.reconstructionWriters");
        String oldQueue = System.getProperty("td20.reconstructionQueueBatches");
        String oldBuffer = System.getProperty("td20.reconstructionBufferKeys");
        try {
            System.setProperty("td20.reconstructionQueueBatches", "1");
            System.setProperty("td20.reconstructionBufferKeys", "17");
            for (int writerCount : new int[]{1, 3, 8}) {
                System.setProperty("td20.reconstructionWriters", Integer.toString(writerCount));
                try (var states = new DiskStateSet(directory.resolve("writers-" + writerCount))) {
                    for (int budget = 8; budget <= 11; budget++) {
                        int moves = budget;
                        states.addGenerated(moves, output -> {
                            for (int repetition = 0; repetition < 3; repetition++) parents.forEach(output);
                        });
                    }
                    assertEquals(expected.size(), states.seal());
                    assertEquals(List.copyOf(expected), keys(states));
                }
            }
        } finally {
            restore("td20.reconstructionWriters", oldWriters);
            restore("td20.reconstructionQueueBatches", oldQueue);
            restore("td20.reconstructionBufferKeys", oldBuffer);
        }
    }

    @Test void concurrentGeneratedBatchesMatchPerKeyInsertionAndReopen(@TempDir Path directory) throws Exception {
        var parents = new ArrayList<CyclicTargetPair>();
        ErikssonInverseReconstruction.preimages(ActiveErikssonAvoidanceTest.pair(5), 1, parents::add);
        assertFalse(parents.isEmpty());
        var lastPair = ActiveErikssonAvoidanceTest.pair(3);
        String oldBuffer = System.getProperty("td20.reconstructionBufferKeys");
        String oldGeneratedBatch = System.getProperty("td20.reconstructionGeneratedBatchKeys");
        // This test stresses producer remainders and cross-batch duplicates.
        // Keep a useful deduplication window instead of ingesting one SST per
        // repeated reference insertion; tiny writer buffers are covered above.
        System.setProperty("td20.reconstructionBufferKeys", "4096");
        System.setProperty("td20.reconstructionGeneratedBatchKeys", "1024");
        Path batchedPath = directory.resolve("batched");
        List<String> expected;
        try {
            try (var baseline = new DiskStateSet(directory.resolve("baseline"));
                 var batched = new DiskStateSet(batchedPath)) {
                for (int budget = 8; budget <= 11; budget++) {
                    for (int i = 0; i < 8193; i++) baseline.add(parents.get(i % parents.size()), budget);
                    baseline.add(lastPair, budget);
                }
                expected = keys(baseline);
                var start = new CountDownLatch(1);
                try (var workers = Executors.newFixedThreadPool(8)) {
                    var futures = new ArrayList<Future<?>>();
                    for (int task = 0; task < 8; task++) {
                        int budget = 8 + task % 4;
                        futures.add(workers.submit(() -> {
                            try {
                                if (!start.await(30, TimeUnit.SECONDS)) throw new AssertionError("Workers did not start");
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(failure);
                            }
                            for (int first = 0; first < 8193; first += 257) {
                                int begin = first, end = Math.min(first + 257, 8193);
                                batched.addGenerated(budget, output -> {
                                    for (int i = begin; i < end; i++) {
                                        var pair = parents.get(i % parents.size());
                                        output.accept(i % 2 == 0 ? pair : pair.canonicalRepresentative());
                                    }
                                });
                            }
                            // A distinct final key detects a dropped per-thread remainder.
                            batched.addGenerated(budget, output -> output.accept(lastPair));
                        }));
                    }
                    start.countDown();
                    for (var future : futures) future.get(30, TimeUnit.SECONDS);
                }
                assertEquals(expected.size(), batched.seal());
                assertEquals(expected, keys(batched));
                assertThrows(IllegalStateException.class, () -> batched.addGenerated(8, output -> fail("Sealed set accepted a generator")));
            }
            try (var reopened = DiskStateSet.openSealed(batchedPath, expected.size())) {
                assertEquals(expected, keys(reopened));
                assertThrows(IllegalStateException.class, () -> reopened.addGenerated(8, output -> fail("Read-only set accepted a generator")));
            }
        } finally {
            restore("td20.reconstructionBufferKeys", oldBuffer);
            restore("td20.reconstructionGeneratedBatchKeys", oldGeneratedBatch);
        }
    }

    @Test void closeDrainsPartialBatchesAcrossGenerationCalls(@TempDir Path directory) {
        var pair = ActiveErikssonAvoidanceTest.pair(3);
        Path path = directory.resolve("closed");
        try (var states = new DiskStateSet(path)) {
            states.addGenerated(8, output -> output.accept(pair));
            states.addGenerated(9, output -> output.accept(pair));
        }
        try (var reopened = DiskStateSet.openSealed(path, 2)) {
            assertEquals(List.of(
                    HexFormat.of().formatHex(DiskStateSet.encode(new DiskStateSet.State(pair, 8))),
                    HexFormat.of().formatHex(DiskStateSet.encode(new DiskStateSet.State(pair, 9)))), keys(reopened));
        }
    }

    @Test void failedGenerationCannotSealPartialOutput(@TempDir Path directory) {
        var pair = ActiveErikssonAvoidanceTest.pair(3);
        String oldBuffer = System.getProperty("td20.reconstructionBufferKeys");
        System.setProperty("td20.reconstructionBufferKeys", "1");
        try {
            for (int emitted : new int[]{7, 4300}) {
                try (var states = new DiskStateSet(directory.resolve("failed-" + emitted))) {
                    var failure = new IllegalArgumentException("Inverse generation failed");
                    assertSame(failure, assertThrows(IllegalArgumentException.class, () -> states.addGenerated(8, output -> {
                        for (int i = 0; i < emitted; i++) output.accept(pair);
                        throw failure;
                    })));
                    assertSame(failure, assertThrows(IllegalStateException.class, states::seal).getCause());
                    assertThrows(IllegalStateException.class, () -> states.forEach(state -> fail("Incomplete wave was scanned")));
                    assertThrows(IllegalStateException.class, () -> states.add(pair, 8));
                    assertThrows(IllegalStateException.class, () -> states.addGenerated(8, output -> fail("Failed set accepted more generation")));
                }
            }
        } finally {
            restore("td20.reconstructionBufferKeys", oldBuffer);
        }
    }

    @Test void emptyGenerationAndInvalidBudgetsPreserveSetRules(@TempDir Path directory) {
        var pair = ActiveErikssonAvoidanceTest.pair(3);
        try (var states = new DiskStateSet(directory.resolve("states"))) {
            states.addGenerated(8, output -> { });
            assertThrows(IllegalArgumentException.class, () -> states.addGenerated(0, output -> fail("Invalid budget accepted")));
            assertThrows(IllegalArgumentException.class, () -> states.addGenerated(12, output -> fail("Invalid budget accepted")));
            states.addGenerated(9, output -> output.accept(pair));
            assertEquals(1, states.seal());
        }
    }

    private static List<String> keys(DiskStateSet states) {
        var result = new ArrayList<String>();
        states.forEach(state -> result.add(HexFormat.of().formatHex(DiskStateSet.encode(state))));
        return result;
    }

    private static void restore(String property, String value) {
        if (value == null) System.clearProperty(property); else System.setProperty(property, value);
    }

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
            var sizes = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
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
            assertEquals(java.util.List.of(1,3,3),sizes.stream().sorted().toList());
            assertEquals(3,hits.get());assertEquals(java.util.Set.of(1,3,5,7),misses);
            assertThrows(IllegalStateException.class,()->states.forEachConcurrently(2,
                    batch->new boolean[batch.size()-1],state->fail("Incomplete lookup cannot skip work"),
                    state->fail("Incomplete lookup cannot dispatch work")));
        } finally {
            if(oldBatch==null)System.clearProperty("td20.certificateLookupBatchSize");else System.setProperty("td20.certificateLookupBatchSize",oldBatch);
            if(oldBuffer==null)System.clearProperty("td20.reconstructionBufferKeys");else System.setProperty("td20.reconstructionBufferKeys",oldBuffer);
        }
    }

    @Test void parallelLookupAndCallbacksJoinBeforePublishingTheInventory(@TempDir Path directory) throws Exception {
        String oldBatch = System.getProperty("td20.reconstructionScanBatchKeys");
        System.setProperty("td20.reconstructionScanBatchKeys", "1");
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var states = new DiskStateSet(directory.resolve("states"));
             var executor = Executors.newSingleThreadExecutor()) {
            var pair = ActiveErikssonAvoidanceTest.pair(3);
            for (int budget = 1; budget <= 7; budget++) states.add(pair, budget);
            var observed = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
            var lookupThreads = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
            var scan = executor.submit(() -> states.forEachConcurrently(2, batch -> {
                lookupThreads.add(Thread.currentThread().threadId());
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Lookup did not resume");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(failure);
                }
                return new boolean[batch.size()];
            }, state -> fail("Unexpected cache hit"), state ->
                    assertTrue(observed.add(state.maximumMoves()), "Duplicate scan callback")));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "Lookup batches must run concurrently");
                assertEquals(-1, states.knownSize(), "An unfinished scan is not a checked inventory");
                assertFalse(scan.isDone());
            } finally { release.countDown(); }
            scan.get(10, TimeUnit.SECONDS);
            assertEquals(2, lookupThreads.size());
            assertEquals(java.util.Set.of(1,2,3,4,5,6,7), observed);
            assertEquals(7, states.knownSize());
        } finally {
            release.countDown();
            restore("td20.reconstructionScanBatchKeys", oldBatch);
        }
    }

    @Test void failedParallelLookupCannotPublishAnUncheckedInventory(@TempDir Path directory) {
        String oldBatch = System.getProperty("td20.reconstructionScanBatchKeys");
        System.setProperty("td20.reconstructionScanBatchKeys", "1");
        try (var states = new DiskStateSet(directory.resolve("states"))) {
            var pair = ActiveErikssonAvoidanceTest.pair(3);
            states.add(pair, 8); states.add(pair, 9);
            assertThrows(IllegalStateException.class, () -> states.forEachConcurrently(2,
                    batch -> null, state -> fail("Incomplete lookup cannot close a class"),
                    state -> fail("Incomplete lookup cannot dispatch a class")));
            assertEquals(-1, states.knownSize());
            var completed = new java.util.concurrent.atomic.AtomicInteger();
            states.forEachConcurrently(2, state -> completed.incrementAndGet());
            assertEquals(2, completed.get());
            assertEquals(2, states.knownSize());
        } finally { restore("td20.reconstructionScanBatchKeys", oldBatch); }
    }

    @Test void parallelCheckpointValidationRejectsMalformedFinalRecords(@TempDir Path directory) throws Exception {
        String oldBatch = System.getProperty("td20.reconstructionScanBatchKeys");
        System.setProperty("td20.reconstructionScanBatchKeys", "1");
        try {
            for (boolean invalidValue : new boolean[]{false, true}) {
                Path path = directory.resolve("invalid-" + invalidValue);
                byte[] valid = DiskStateSet.encode(new DiskStateSet.State(ActiveErikssonAvoidanceTest.pair(3), 8));
                byte[] invalid = valid.clone();
                if (invalidValue) invalid[1] = 9; else invalid[0] = 2;
                try (var options = new org.rocksdb.Options().setCreateIfMissing(true);
                     var database = org.rocksdb.RocksDB.open(options, path.toString());
                     var writes = new org.rocksdb.WriteOptions().setDisableWAL(true);
                     var flush = new org.rocksdb.FlushOptions().setWaitForFlush(true)) {
                    database.put(writes, valid, new byte[0]);
                    database.put(writes, invalid, invalidValue ? new byte[]{1} : new byte[0]);
                    database.flush(flush);
                }
                assertThrows(IllegalStateException.class, () -> DiskStateSet.openSealed(path, 2));
                try (var states = DiskStateSet.openSealedForScan(path, 2)) {
                    assertThrows(IllegalStateException.class, () -> states.forEachConcurrently(2, state -> { }));
                    assertEquals(-1, states.knownSize());
                }
            }
        } finally { restore("td20.reconstructionScanBatchKeys", oldBatch); }
    }

    @Test void checkpointCountIsAnExpectationUntilTheMandatoryScanCompletes(@TempDir Path directory) {
        Path path = directory.resolve("states");
        var pair = ActiveErikssonAvoidanceTest.pair(3);
        try (var states = new DiskStateSet(path)) {
            states.add(pair, 8); states.add(pair, 9); assertEquals(2, states.seal());
        }
        assertThrows(IllegalStateException.class, () -> DiskStateSet.openSealedForScan(directory.resolve("missing"), 0));
        try (var states = DiskStateSet.openSealedForScan(path, 3)) {
            assertEquals(3, states.prepareScan());
            assertEquals(-1, states.knownSize());
            assertThrows(IllegalStateException.class, () -> states.forEachConcurrently(2, state -> { }));
            assertEquals(-1, states.knownSize(), "A count mismatch cannot publish a checked inventory");
        }
        try (var states = DiskStateSet.openSealedForScan(path, 2)) {
            assertEquals(-1, states.knownSize());
            assertThrows(IllegalStateException.class, () -> states.add(pair, 10));
            var count = new java.util.concurrent.atomic.AtomicInteger();
            states.forEachConcurrently(2, state -> count.incrementAndGet());
            assertEquals(2, count.get());
            assertEquals(2, states.knownSize());
        }
    }
}
