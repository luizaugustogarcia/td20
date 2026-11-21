package br.unb.cic.td20.verification;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.Range;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.SizeApproximationFlag;
import org.rocksdb.Slice;
import org.rocksdb.WriteOptions;

import static org.junit.jupiter.api.Assertions.*;

class ParallelStateScannerTest {
    private static final byte[] EMPTY = {};
    static { RocksDB.loadLibrary(); }

    @Test void emptyAndSingletonInventoriesIncludeEmptyAndUnsignedKeys(@TempDir Path directory)
            throws Exception {
        try (var store = new Store(directory.resolve("empty"))) {
            assertEquals(0, scan(store, 4, 8, 2, 0, batch -> fail("Empty snapshot has no callbacks")));
            assertThrows(IllegalStateException.class, () -> scan(store, 4, 8, 2, 1, batch -> {}));
        }
        var keys = List.of(EMPTY, new byte[]{0}, new byte[]{(byte) 255}, new byte[]{(byte) 255, 0});
        for (int i = 0; i < keys.size(); i++) {
            try (var store = new Store(directory.resolve("singleton-" + i))) {
                var key = keys.get(i);
                store.put(key);
                var seen = new ArrayList<byte[]>();
                assertEquals(1, scan(store, 4, 8, 2, 1, seen::addAll));
                assertEquals(1, seen.size());
                assertArrayEquals(key, seen.getFirst());
            }
        }
    }

    @Test void prefixAndUnsignedBoundariesAreVisitedExactlyOnce(@TempDir Path directory) throws Exception {
        var keys = List.of(EMPTY, new byte[]{0}, new byte[]{0, 0}, new byte[]{0, 0, 0},
                new byte[]{0, 1}, new byte[]{1}, new byte[]{1, 0}, new byte[]{127},
                new byte[]{(byte) 128}, new byte[]{(byte) 128, 0}, new byte[]{(byte) 254},
                new byte[]{(byte) 255}, new byte[]{(byte) 255, 0},
                new byte[]{(byte) 255, (byte) 255}, new byte[]{(byte) 255, (byte) 255, 0});
        try (var store = new Store(directory.resolve("boundaries"))) {
            for (var key : keys) store.put(key);
            store.flush();
            for (int ranges : new int[]{1, 4, 32})
                assertCoverage(store, keys, 4, ranges, 3);
        }
    }

    @Test void randomVariableLengthKeysMatchSerialMergedInventoryAcrossSsts(@TempDir Path directory)
            throws Exception {
        var keys = new TreeSet<byte[]>(Arrays::compareUnsigned);
        var random = new Random(20261121L);
        try (var store = new Store(directory.resolve("merged"))) {
            for (int wave = 0; wave < 3; wave++) {
                for (int i = 0; i < 1500; i++) {
                    var key = new byte[random.nextInt(1, 24)];
                    random.nextBytes(key);
                    keys.add(key);
                    store.put(key);
                }
                // Duplicate logical keys appear in multiple flushed tables.
                for (var key : keys) store.put(key);
                store.flush();
            }
            for (var key : List.copyOf(keys).subList(0, 17)) {
                store.database.delete(store.writes, key);
                keys.remove(key);
            }
            store.flush();
            var serial = new ArrayList<byte[]>();
            try (var iterator = store.database.newIterator()) {
                for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) serial.add(iterator.key());
                iterator.status();
            }
            assertEquals(encoded(keys), encoded(serial));
            assertCoverage(store, serial, 1, 4, 31);
            assertCoverage(store, serial, 4, 8, 31);
        }
    }

    @Test void zeroSizeEstimatesDistributeWorkWithoutLeavingOneHugeTail(@TempDir Path directory)
            throws Exception {
        try (var store = new Store(directory.resolve("memtable"))) {
            for (int i = 0; i < 1024; i++) store.put(key(i));
            try (var start = new Slice(EMPTY); var end = new Slice(new byte[]{(byte) 255})) {
                assertEquals(0, store.database.getApproximateSizes(List.of(new Range(start, end)),
                        SizeApproximationFlag.INCLUDE_FILES)[0], "Exercise the zero-estimate fallback");
            }
            var sizes = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
            assertEquals(1024, scan(store, 4, 4, 2048, 1024, batch -> sizes.add(batch.size())));
            assertEquals(16, sizes.size());
            assertTrue(sizes.stream().allMatch(size -> size == 64), "Ranges must share uniform keys evenly");
        }
    }

    @Test void valuesAreValidatedEvenWithZeroLengthDestinationBuffer(@TempDir Path directory)
            throws Exception {
        try (var store = new Store(directory.resolve("bad-value"))) {
            store.database.put(store.writes, key(1), new byte[]{42});
            store.flush();
            try (var iterator = store.database.newIterator()) {
                iterator.seekToFirst();
                assertEquals(1, iterator.value(EMPTY), "Buffer API must report full value length");
            }
            assertThrows(IllegalStateException.class, () -> scan(store, 4, 4, 3, 1,
                    batch -> fail("Malformed value cannot reach the callback")));
        }
    }

    @Test void malformedKeysCannotBeOmittedFromValidation(@TempDir Path directory) throws Exception {
        var keys = List.of(EMPTY, new byte[]{0}, new byte[]{2, 8, 3, 0}, new byte[]{(byte) 255});
        for (int i = 0; i < keys.size(); i++) {
            try (var store = new Store(directory.resolve("bad-key-" + i))) {
                store.put(keys.get(i));
                assertThrows(IllegalStateException.class, () -> scan(store, 4, 8, 3, 1, batch -> {
                    for (var key : batch) CheckerStateStorage.decode(key, EMPTY);
                }));
            }
        }
    }

    @Test void snapshotRetainsOriginalKeysAndValuesAcrossWritesDeletesAndFlushes(@TempDir Path directory)
            throws Exception {
        try (var store = new Store(directory.resolve("snapshot"))) {
            var keys = new ArrayList<byte[]>();
            for (int i = 0; i < 512; i++) { keys.add(key(i)); store.put(key(i)); }
            store.flush();
            var changed = new AtomicBoolean();
            var seen = ConcurrentHashMap.<String>newKeySet();
            // One worker ensures the changed key's later range opens after the write.
            assertEquals(512, scan(store, 1, 4, 17, 512, batch -> {
                if (changed.compareAndSet(false, true)) {
                    try {
                        store.put(EMPTY);
                        store.put(new byte[]{(byte) 255, 0});
                        store.database.delete(store.writes, key(256));
                        store.database.put(store.writes, key(300), new byte[]{99});
                        store.flush();
                    } catch (RocksDBException failure) { throw new IllegalStateException(failure); }
                }
                for (var key : batch) assertTrue(seen.add(HexFormat.of().formatHex(key)));
            }));
            assertTrue(changed.get());
            assertEquals(encoded(keys), seen);
        }
    }

    @Test void callbackFailureJoinsAndCancelsAllActiveWorkers(@TempDir Path directory) throws Exception {
        try (var store = new Store(directory.resolve("failure"))) {
            for (int i = 0; i < 512; i++) store.put(key(i));
            var entered = new CountDownLatch(2);
            var blocked = new CountDownLatch(1);
            var callbacks = new AtomicInteger();
            var active = new AtomicInteger();
            var threads = ConcurrentHashMap.<Thread>newKeySet();
            var failure = new IllegalArgumentException("callback failure");
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    var thrown = assertThrows(IllegalStateException.class, () -> scan(store, 2, 4, 1, 512, batch -> {
                        int index = callbacks.getAndIncrement();
                        active.incrementAndGet();
                        threads.add(Thread.currentThread());
                        entered.countDown();
                        try {
                            if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Workers did not overlap");
                            if (index == 0) throw failure;
                            if (!blocked.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker was not canceled");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        } finally { active.decrementAndGet(); }
                    }));
                    assertSame(failure, thrown.getCause());
                });
                assertEquals(0, active.get(), "Scanner must join callbacks before returning failure");
                assertTrue(threads.stream().noneMatch(Thread::isAlive));
                assertEquals(512, scan(store, 2, 4, 17, 512, batch -> {}));
            } finally { blocked.countDown(); }
        }
    }

    @Test void expectedCountsAndInvalidParametersRejectIncompleteResults(@TempDir Path directory)
            throws Exception {
        try (var store = new Store(directory.resolve("counts"))) {
            for (int i = 0; i < 11; i++) store.put(key(i));
            assertEquals(11, scan(store, 4, 4, 3, -1, batch -> {}));
            for (long expected : new long[]{0, 10, 12, Long.MAX_VALUE})
                assertThrows(IllegalStateException.class, () -> scan(store, 4, 4, 3, expected, batch -> {}));
            assertThrows(IllegalArgumentException.class, () -> scan(store, 0, 4, 3, -1, batch -> {}));
            assertThrows(IllegalArgumentException.class, () -> scan(store, 4, 0, 3, -1, batch -> {}));
            assertThrows(IllegalArgumentException.class, () -> scan(store, 4, 4, 0, -1, batch -> {}));
            assertThrows(IllegalArgumentException.class, () -> scan(store, 4, 4, 3, -2, batch -> {}));
            assertThrows(ArithmeticException.class, () -> scan(store, Integer.MAX_VALUE, 2, 3, -1, batch -> {}));
        }
    }

    private static void assertCoverage(Store store, List<byte[]> keys, int workers, int ranges, int batchKeys) {
        var seen = ConcurrentHashMap.<String>newKeySet();
        assertEquals(keys.size(), scan(store, workers, ranges, batchKeys, keys.size(), batch -> {
            assertFalse(batch.isEmpty());
            assertTrue(batch.size() <= batchKeys);
            for (var key : batch) assertTrue(seen.add(HexFormat.of().formatHex(key)), "Duplicate scan key");
        }));
        assertEquals(encoded(keys), seen);
    }

    private static java.util.Set<String> encoded(Iterable<byte[]> keys) {
        var result = new java.util.HashSet<String>();
        for (var key : keys) result.add(HexFormat.of().formatHex(key));
        return result;
    }

    private static long scan(Store store, int workers, int ranges, int batch, long expected,
                             java.util.function.Consumer<List<byte[]>> consumer) {
        return ParallelStateScanner.scan(store.database, workers, ranges, batch, expected, "Scanner test", consumer);
    }

    private static byte[] key(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }

    private static final class Store implements AutoCloseable {
        final Options options = new Options().setCreateIfMissing(true).setDisableAutoCompactions(true);
        final WriteOptions writes = new WriteOptions().setDisableWAL(true);
        final RocksDB database;
        Store(Path directory) throws RocksDBException { database = RocksDB.open(options, directory.toString()); }
        void put(byte[] key) throws RocksDBException { database.put(writes, key, EMPTY); }
        void flush() throws RocksDBException {
            try (var flush = new FlushOptions().setWaitForFlush(true)) { database.flush(flush); }
        }
        @Override public void close() throws RocksDBException {
            try { flush(); }
            finally { database.close(); writes.close(); options.close(); }
        }
    }
}
