package br.unb.cic.td20.verification;

import br.unb.cic.td20.model.PrimitivePair;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class CheckerStateStoragePersistenceTest {

    @Test
    void repeatedConcurrentKeysIngestOnceAndKeepBudgetsDistinct(@TempDir final Path directory) throws Exception {
        val pair = PrimitivePair.of(new int[] { 1, 2, 0 }, new int[] { 0, 1, 2 });
        val expected = List.of(new CheckerStateStorage.State(pair, 8), new CheckerStateStorage.State(pair, 11));
        final Path databaseDirectory = directory.resolve("concurrent");

        try (val properties = new Properties("td20.reconstructionWriters",
                "3",
                "td20.reconstructionBufferKeys",
                "64",
                "td20.reconstructionQueueBatches",
                "3",
                "td20.dbWriteBatchEntries",
                "1");
            val states = new CheckerStateStorage(databaseDirectory)) {
            final int workers = 4, copies = 30;

            try (val executor = Executors.newFixedThreadPool(workers)) {
                val jobs = new ArrayList<java.util.concurrent.Future<?>>();
                for (int worker = 0; worker < workers; worker++) {
                    jobs.add(executor.submit(() -> {
                        for (int copy = 0; copy < copies; copy++) {
                            for (val state : expected) states.add(state.pair(), state.maximumMoves());
                        }
                    }));
                }
                for (val job : jobs) {
                    job.get(30, java.util.concurrent.TimeUnit.SECONDS);
                }
            }

            assertEquals(2, states.seal());
            assertEquals(2, states.writeProgress().ingestedKeys());
            assertEquals(2, states.writeProgress().ingestedTables());
            assertEquals((long) workers * copies * expected.size() - expected.size(),
                    states.writeProgress().duplicateKeys());
        }

        assertPersistedInventory(databaseDirectory, expected);
    }

    @Test
    void mixedExistingAndAbsentKeysSurviveMembershipBatchBoundaries(@TempDir final Path directory) throws Exception {
        final int flushKeys = 4097;
        val expected = distinctStates(2 * flushKeys);
        final Path databaseDirectory = directory.resolve("membership-batches");

        try (val properties = new Properties("td20.reconstructionWriters",
                "1",
                "td20.reconstructionBufferKeys",
                Integer.toString(flushKeys),
                "td20.reconstructionQueueBatches",
                "4",
                "td20.dbWriteBatchEntries",
                Integer.toString(flushKeys));
            val states = new CheckerStateStorage(databaseDirectory)) {
            // Establish an alternating existing/absent pattern in unsigned key order.
            for (int index = 0; index < expected.size(); index += 2) {
                val state = expected.get(index);
                states.add(state.pair(), state.maximumMoves());
            }

            assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> {
                        while (states.writeProgress().ingestedTables() == 0) {
                            Thread.sleep(1);
                        }
                    });
            assertEquals(flushKeys, states.writeProgress().ingestedKeys());
            for (val state : expected) {
                states.add(state.pair(), state.maximumMoves());
            }

            assertEquals(expected.size(), states.seal());
            assertEquals(expected.size(), states.writeProgress().ingestedKeys());
            assertEquals(flushKeys, states.writeProgress().duplicateKeys());
            assertEquals(3, states.writeProgress().ingestedTables());
        }

        assertPersistedInventory(databaseDirectory, expected);
    }

    @Test
    void malformedExistingValueFailsBeforePublishingAnInventory(@TempDir final Path directory) throws Exception {
        try (val properties = new Properties("td20.reconstructionWriters", "1", "td20.dbWriteBatchEntries", "1")) {
            val states = new CheckerStateStorage(directory.resolve("malformed"));

            try {
                val pair = PrimitivePair.of(new int[] { 1, 2, 0 }, new int[] { 0, 1, 2 });
                val state = new CheckerStateStorage.State(pair, 8);
                val field = CheckerStateStorage.class.getDeclaredField("database");
                field.setAccessible(true);

                val database = (RocksDB) field.get(states);

                try (val writes = new WriteOptions().setDisableWAL(true)) {
                    database.put(writes, CheckerStateStorage.encode(state), new byte[] { 1 });
                }

                states.add(pair, state.maximumMoves());

                val failure = assertThrows(IllegalStateException.class, states::seal);
                boolean malformed = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    malformed |= "Malformed persisted reconstruction value".equals(cause.getMessage());
                }

                assertTrue(malformed, "An existing malformed value must fail the exact membership check");
                assertEquals(-1, states.knownSize());
                assertEquals(0, states.writeProgress().ingestedKeys());
                assertEquals(0, states.writeProgress().ingestedTables());
                assertThrows(IllegalStateException.class, states::close);
            } finally {
                // A failed writer still owns native resources until close joins it.
                try {
                    states.close();
                } catch (final IllegalStateException expected) {
                }
            }
        }
    }

    private static List<CheckerStateStorage.State> distinctStates(final int count) {
        final int n = 13;
        val random = new Random(20261122L);
        val states = new TreeMap<byte[], CheckerStateStorage.State>(Arrays::compareUnsigned);
        val beta = new int[n];
        for (int symbol = 0; symbol < n; symbol++) {
            beta[symbol] = symbol;
        }
        while (states.size() < count) {
            final int[] target = beta.clone();
            for (int index = n - 1; index > 0; index--) {
                final int other = random.nextInt(index + 1), old = target[index];
                target[index] = target[other];
                target[other] = old;
            }

            val omega = new int[n];
            for (int index = 0; index < n; index++) {
                omega[(target[index] + 1) % n] = target[(index + 1) % n];
            }

            val pair = PrimitivePair.of(omega, beta);
            if (!pair.isFixedPointFree()) {
                continue;
            }

            val state = new CheckerStateStorage.State(pair, 10);
            states.putIfAbsent(CheckerStateStorage.encode(state), state);
        }

        return List.copyOf(states.values());
    }

    private static void assertPersistedInventory(final Path directory, final List<CheckerStateStorage.State> expected) throws Exception {
        val keys = new HashSet<String>();
        for (val state : expected) {
            assertTrue(keys.add(HexFormat.of().formatHex(CheckerStateStorage.encode(state))));
        }

        long count = 0;

        try (val options = new Options();
            val database = RocksDB.openReadOnly(options, directory.toString());
            val reads = new ReadOptions().setFillCache(false);
            val iterator = database.newIterator(reads)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                final byte[] key = iterator.key();
                assertTrue(keys.remove(HexFormat.of().formatHex(key)), "Unexpected or repeated persisted key");
                assertArrayEquals(key, CheckerStateStorage.encode(CheckerStateStorage.decode(key, iterator.value())));
                count++;
            }

            iterator.status();
        }

        assertEquals(expected.size(), count);
        assertTrue(keys.isEmpty(), "Every generated valid class must survive persistence and reopening");
    }

    private static final class Properties implements AutoCloseable {

        private final LinkedHashMap<String, String> previous = new LinkedHashMap<>();

        Properties(final String... values) {
            for (int index = 0; index < values.length; index += 2) {
                previous.put(values[index], System.getProperty(values[index]));
                System.setProperty(values[index], values[index + 1]);
            }
        }

        @Override
        public void close() {
            previous.forEach((name, value) -> {
                if (value == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, value);
                }
            });
        }
    }
}
