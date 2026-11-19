package br.unb.cic.td20.avoidance;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteOptions;
import static org.junit.jupiter.api.Assertions.*;

class DiskStateCountingTest {
    @Test void keyOnlyCountDrainsRemaindersAndDoesNotReplayAdvisoryObserver(@TempDir Path directory) {
        var observed = new AtomicInteger();
        String oldBatch = System.getProperty("td20.reconstructionGeneratedBatchKeys");
        System.setProperty("td20.reconstructionGeneratedBatchKeys","17");
        var pair = ActiveErikssonAvoidanceTest.pair(5);
        try (var states = new DiskStateSet(directory.resolve("states"),keys -> observed.addAndGet(keys.size()))) {
            states.addGenerated(8,output -> { output.accept(pair); output.accept(pair); });
            states.addGenerated(9,output -> { output.accept(pair); output.accept(pair); });
            assertEquals(4,states.writeProgress().generatedKeyOccurrences());
            assertEquals(0,states.writeProgress().submittedKeyOccurrences());
            assertEquals(-1,states.knownSize());
            states.prepareScan();
            assertEquals(4,observed.get());
            assertEquals(2,states.countKeys());
            assertEquals(2,states.knownSize());
            assertEquals(4,observed.get(),"Counting must not publish persisted keys to prefetch again");
            assertEquals(2,states.countKeys());
            assertEquals(4,observed.get());
            var progress = states.writeProgress();
            assertEquals(4,progress.generatedKeyOccurrences());
            assertEquals(4,progress.submittedKeyOccurrences());
            assertEquals(4,progress.consumedKeyOccurrences());
            assertEquals(0,progress.queuedKeyOccurrences());
            assertEquals(2,progress.ingestedKeyOccurrences());
            assertThrows(IllegalStateException.class,()->states.add(pair,10));
        } finally { restore("td20.reconstructionGeneratedBatchKeys",oldBatch); }
    }

    @Test void overlappingSstsHaveOneLogicalKeyButSeveralSuccessfulWriteOccurrences(@TempDir Path directory) {
        String oldWriters = System.getProperty("td20.reconstructionWriters");
        String oldBuffer = System.getProperty("td20.reconstructionBufferKeys");
        System.setProperty("td20.reconstructionWriters","1");
        System.setProperty("td20.reconstructionBufferKeys","3");
        Path path = directory.resolve("states");
        try {
            try (var states = new DiskStateSet(path)) {
                var pair = ActiveErikssonAvoidanceTest.pair(5);
                for (int repetition = 0; repetition < 2; repetition++)
                    for (int budget = 8; budget <= 10; budget++) states.add(pair,budget);
                assertEquals(3,states.countKeys());
                var writes = states.writeProgress();
                assertEquals(6,writes.generatedKeyOccurrences());
                assertEquals(6,writes.submittedKeyOccurrences());
                assertEquals(6,writes.consumedKeyOccurrences());
                assertEquals(6,writes.ingestedKeyOccurrences());
                assertEquals(2,writes.ingestedTables());
            }
            try (var reopened = DiskStateSet.openSealedForScan(path,3)) { assertEquals(3,reopened.countKeys()); }
            try (var wrong = DiskStateSet.openSealedForScan(path,4)) {
                assertThrows(IllegalStateException.class,wrong::countKeys);
                assertEquals(-1,wrong.knownSize(),"A failed inventory expectation must not publish a count");
            }
        } finally {
            restore("td20.reconstructionWriters",oldWriters);
            restore("td20.reconstructionBufferKeys",oldBuffer);
        }
    }

    @Test void countingDoesNotDecodeRecordsWhileMandatoryProcessingStillRejectsMalformedData(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("states");
        try (var states = new DiskStateSet(path)) { states.prepareScan(); }
        try (var options = new Options().setCreateIfMissing(false);
             var writes = new WriteOptions().setDisableWAL(true);
             var flush = new org.rocksdb.FlushOptions().setWaitForFlush(true);
             var database = RocksDB.open(options,path.toString())) {
            database.put(writes,new byte[]{0},new byte[]{7});
            database.flush(flush);
        }
        try (var states = DiskStateSet.openCompletedForScan(path)) {
            assertEquals(1,states.countKeys(),"Key-only count deliberately performs no state validation");
            assertThrows(IllegalStateException.class,states::seal,"Legacy sealing must still validate after a key-only count");
            assertThrows(IllegalStateException.class,()->states.forEach(state -> fail("Malformed records cannot reach processing")));
        }
    }

    @Test void failureCannotBecomeACompletedCountAndEmptySetCountsZero(@TempDir Path directory) {
        try (var empty = new DiskStateSet(directory.resolve("empty"))) {
            assertEquals(0,empty.countKeys()); assertEquals(0,empty.knownSize());
            assertEquals(new DiskStateSet.WriteProgress(0,0,0,0,0),empty.writeProgress());
        }
        try (var failed = new DiskStateSet(directory.resolve("failed"))) {
            var pair = ActiveErikssonAvoidanceTest.pair(5);
            assertThrows(IllegalArgumentException.class,()->failed.addGenerated(8,output -> {
                output.accept(pair); throw new IllegalArgumentException("interrupted generation");
            }));
            assertEquals(1,failed.writeProgress().generatedKeyOccurrences());
            assertThrows(IllegalStateException.class,failed::countKeys);
            assertEquals(-1,failed.knownSize());
        }
    }

    @Test void concurrentSourcesCountDuplicateOccurrencesAndPerWorkerRemainders(@TempDir Path directory) throws Exception {
        String oldBatch = System.getProperty("td20.reconstructionGeneratedBatchKeys");
        System.setProperty("td20.reconstructionGeneratedBatchKeys","17");
        int workers = Math.min(4,Runtime.getRuntime().availableProcessors());
        int tasks = workers * 3;
        try (var states = new DiskStateSet(directory.resolve("states"));
             var executor = java.util.concurrent.Executors.newFixedThreadPool(workers)) {
            var pair = ActiveErikssonAvoidanceTest.pair(5);
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int task = 0; task < tasks; task++) {
                int budget = 8 + task % 3;
                jobs.add(executor.submit(()->states.addGenerated(budget,output -> {
                    for (int repetition = 0; repetition < 9; repetition++) output.accept(pair);
                })));
            }
            for (var job : jobs) job.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(9L * tasks,states.writeProgress().generatedKeyOccurrences());
            assertEquals(3,states.countKeys());
            var writes = states.writeProgress();
            assertEquals(9L * tasks,writes.generatedKeyOccurrences());
            assertEquals(9L * tasks,writes.submittedKeyOccurrences());
            assertEquals(9L * tasks,writes.consumedKeyOccurrences());
            assertEquals(0,writes.queuedKeyOccurrences());
            assertEquals(3,writes.ingestedKeyOccurrences());
        } finally { restore("td20.reconstructionGeneratedBatchKeys",oldBatch); }
    }

    private static void restore(String property,String prior) {
        if (prior == null) System.clearProperty(property); else System.setProperty(property,prior);
    }
}
