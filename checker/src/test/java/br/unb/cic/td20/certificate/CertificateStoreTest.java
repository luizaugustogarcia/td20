package br.unb.cic.td20.certificate;

import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.runtime.RocksDbMemoryBudget;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.rocksdb.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CertificateStoreTest {

    static {
        RocksDB.loadLibrary();
    }

    private static final CyclicTargetPair PAIR = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));

    private static void database(final Path path, final byte[] value, final boolean marker) throws Exception {
        database(path, value, marker ? "eriksson-three-v1" : null, PAIR);
    }

    private static void database(final Path path, final byte[] value, final String marker, final CyclicTargetPair pair) throws Exception {
        try (val options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            val first = new ColumnFamilyOptions();
            val second = new ColumnFamilyOptions()) {
            val handles = new ArrayList<ColumnFamilyHandle>();

            try (val db = RocksDB.open(options,
                    path.toString(),
                    List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, first),
                            new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), second)),
                    handles);
                val writes = new WriteOptions().setDisableWAL(true);
                val flush = new FlushOptions().setWaitForFlush(true)) {
                if (marker != null) {
                    db.put(handles.get(0),
                            writes,
                            "td20-schema".getBytes(StandardCharsets.UTF_8),
                            marker.getBytes(StandardCharsets.UTF_8));
                    // A separate SST lets cache tests observe the witness block's first read.
                    db.flush(flush, handles);
                }
                if (value != null) {
                    db.put(handles.get(0), writes, CertificateCodec.keyFor(pair), value);
                }

                db.flush(flush, handles);
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({ "root,true", "endpoint,true", "reconstruction,true", "root,false", "endpoint,false", "reconstruction,false" })
    void phaseDefaultsCacheDataBlocksAndAllowExplicitOptOut(final String phase,
            final boolean cacheData,
            @TempDir final Path path) throws Exception {
        final String phaseProperty = switch(phase) {
            case "root" ->
                "td20.certificateRootFillCache";
            case "endpoint" ->
                "td20.certificateEndpointFillCache";
            case "reconstruction" ->
                "td20.certificateReconstructionFillCache";
            default ->
                throw new IllegalArgumentException(phase);
        };
        val previous = new HashMap<String, String>();
        for (final String property : List.of(phaseProperty,
                "td20.rocksDbFillCache",
                "td20.certificateCacheIndexAndFilterBlocks")) {
            previous.put(property, System.getProperty(property));
        }

        try {
            System.setProperty("td20.rocksDbFillCache", "true");
            System.setProperty("td20.certificateCacheIndexAndFilterBlocks", "false");
            if (cacheData) {
                System.clearProperty(phaseProperty);
            } else {
                System.setProperty(phaseProperty, "false");
            }

            database(path.resolve("db"),
                    CertificateCodec.resultFor(SortingCertificate.complete(List.of(Cycle.of(0, 1, 2)))),
                    true);

            try (val store = new CertificateStore(path.resolve("db"));
                val memory = RocksDbMemoryBudget.acquire()) {
                store.readPhase(phase);

                final long before = memory.cache().getUsage();
                assertArrayEquals(new boolean[] { true },
                        store.findAllPrimitive(List.of(LegacyPairs.fromLegacy(PAIR)), 1));

                final long after = memory.cache().getUsage();
                if (cacheData) {
                    assertTrue(after > before, "Bulk reads must populate the data-block cache");
                } else {
                    assertEquals(before, after, "Bulk reads must honor the phase's explicit cache opt-out");
                }
            }
        } finally {
            previous.forEach((property, value) -> {
                if (value == null) {
                    System.clearProperty(property);
                } else {
                    System.setProperty(property, value);
                }
            });
        }
    }

    @Test
    void acceptsReplayedWitness(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),
                CertificateCodec.resultFor(SortingCertificate.complete(List.of(Cycle.of(0, 1, 2)))),
                true);

        try (val store = new CertificateStore(path.resolve("db"))) {
            assertEquals(1, store.require(PAIR, 1).size());

            val missing = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2, 3, 4)), Cycle.of(0, 1, 2, 3, 4));
            assertArrayEquals(new boolean[] { true, false, true }, store.findAll(List.of(PAIR, missing, PAIR), 1));
            assertArrayEquals(new boolean[] { true, false, true },
                    store.findAllPrimitive(List.of(LegacyPairs.fromLegacy(PAIR), LegacyPairs.fromLegacy(missing), LegacyPairs.fromLegacy(PAIR)),
                            1));
            assertArrayEquals(new boolean[] { false, false }, store.findAll(List.of(PAIR, missing), 0));
            assertFalse(store.hasObstruction(PAIR));
        }
    }

    @Test
    void zeroMoveClosureIsPresentAndItsDiameterConsumesBudget(@TempDir final Path path) throws Exception {
        database(path.resolve("db"), CertificateCodec.resultFor(SortingCertificate.closure(List.of())), true);

        try (val store = new CertificateStore(path.resolve("db"))) {
            assertTrue(store.find(PAIR, 0).isEmpty());
            assertArrayEquals(new boolean[] { false }, store.findAllPrimitive(List.of(LegacyPairs.fromLegacy(PAIR)), 0));
            assertTrue(store.find(PAIR, 1).isPresent());
            assertArrayEquals(new boolean[] { true }, store.findAllPrimitive(List.of(LegacyPairs.fromLegacy(PAIR)), 1));
            assertTrue(store.require(PAIR, 1).isEmpty());
        }
    }

    @Test
    void asynchronousSlicesPreserveDuplicateMissingAndCanonicalReplayPositions(@TempDir final Path path) throws Exception {
        final String property = "td20.certificateLookupBatchSize", previous = System.getProperty(property);
        System.setProperty(property, "2");

        try {
            database(path.resolve("db"),
                    CertificateCodec.resultFor(SortingCertificate.complete(List.of(Cycle.of(0, 1, 2)))),
                    true);

            val missing = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2, 3, 4)), Cycle.of(0, 1, 2, 3, 4));
            val rotated = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(1, 2, 0));
            val pairs = List.of(PAIR, missing, PAIR, rotated, missing).stream().map(LegacyPairs::fromLegacy).toList();

            try (val store = new CertificateStore(path.resolve("db"))) {
                assertArrayEquals(new boolean[] { true, false, true, true, false }, store.findAllPrimitiveAsync(pairs, 1).join());
                assertArrayEquals(new boolean[5], store.findAllPrimitiveAsync(pairs, 0).join());
                assertArrayEquals(store.findAllPrimitive(pairs, 1), store.findAllPrimitiveAsync(pairs, 1).join());
            }
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void invalidResultKindIsRejected(@TempDir final Path path) throws Exception {
        database(path.resolve("db"), new byte[] { 2, 0 }, true);

        try (val store = new CertificateStore(path.resolve("db"))) {
            assertThrows(IllegalArgumentException.class, () -> store.find(PAIR, 10));
            assertThrows(IllegalArgumentException.class, () -> store.findAll(List.of(PAIR), 10));
            assertThrows(IllegalArgumentException.class,
                    () -> store.findAllPrimitive(List.of(LegacyPairs.fromLegacy(PAIR)), 10));
        }
    }

    @Test
    void longerValidWitnessIsUnavailableAtSmallerBudget(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),
                CertificateCodec.resultFor(SortingCertificate.complete(List.of(Cycle.of(0, 1, 2), Cycle.of(1, 0, 2), Cycle.of(0, 1, 2)))),
                true);

        try (val store = new CertificateStore(path.resolve("db"))) {
            assertTrue(store.find(PAIR, 1).isEmpty());
            assertEquals(3, store.require(PAIR, 3).size());
            assertArrayEquals(new boolean[] { false }, store.findAllPrimitive(List.of(LegacyPairs.fromLegacy(PAIR)), 1));
            assertArrayEquals(new boolean[] { true }, store.findAllPrimitive(List.of(LegacyPairs.fromLegacy(PAIR)), 3));
        }
    }

    @Test
    void malformedWitnessFailsEvenAboveRequestedBudget(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),
                CertificateCodec.resultFor(SortingCertificate.complete(List.of(Cycle.of(0, 1, 2), Cycle.of(1, 0, 2)))),
                true);

        try (val store = new CertificateStore(path.resolve("db"))) {
            assertThrows(IllegalArgumentException.class, () -> store.find(PAIR, 1));
            assertThrows(IllegalArgumentException.class, () -> store.findAll(List.of(PAIR), 1));
            assertThrows(IllegalArgumentException.class, () -> store.findAllPrimitive(List.of(LegacyPairs.fromLegacy(PAIR)), 1));
        }
    }

    @Test
    void lookupReplaysInTheCanonicalFrameForRotatedCurrentCycles(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),
                CertificateCodec.resultFor(SortingCertificate.complete(List.of(Cycle.of(0, 1, 2)))),
                true);

        val rotated = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(1, 2, 0));
        val decoded = CertificateCodec.pairForKey(CertificateCodec.keyFor(PAIR), 3);
        val missing = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2, 3, 4)), Cycle.of(0, 1, 2, 3, 4));

        try (val store = new CertificateStore(path.resolve("db"))) {
            assertEquals(List.of(Cycle.of(0, 1, 2)), store.require(rotated, 1));
            assertArrayEquals(new boolean[] { true, true, false }, store.findAll(List.of(rotated, decoded, missing), 1));
            assertArrayEquals(new boolean[] { true, true, false },
                    store.findAllPrimitive(List.of(rotated, decoded, missing).stream().map(LegacyPairs::fromLegacy).toList(), 1));
        }
    }

    @Test
    void projectMaximumIsValidatedBeforeRequestedBudgetComparison(@TempDir final Path path) throws Exception {
        val moves = new ArrayList<Cycle>();
        for (int i = 0; i < 4; i++) {
            moves.add(Cycle.of(0, 1, 2));
            moves.add(Cycle.of(1, 0, 2));
        }

        val pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2, 3, 4, 5, 6)), Cycle.of(0, 1, 2, 3, 4, 5, 6)).canonicalRepresentative();
        // Eight moves return to this pair; TD(6)=4 makes the closure require twelve.
        database(path.resolve("db"),
                CertificateCodec.resultFor(SortingCertificate.closure(moves)),
                "eriksson-three-v1",
                pair);

        try (val store = new CertificateStore(path.resolve("db"))) {
            assertThrows(IllegalArgumentException.class, () -> store.find(pair, 20));
            assertThrows(IllegalArgumentException.class, () -> store.findAll(List.of(pair), 20));
            assertThrows(IllegalArgumentException.class,
                    () -> store.findAllPrimitive(List.of(LegacyPairs.fromLegacy(pair)), 20));
        }
    }

    @Test
    void missingWitnessIsNotInventedFromInventory(@TempDir final Path path) throws Exception {
        database(path.resolve("db"), null, true);

        try (val store = new CertificateStore(path.resolve("db"))) {
            assertTrue(store.find(PAIR, 10).isEmpty());
            assertThrows(IllegalStateException.class, () -> store.require(PAIR, 10));
        }
    }

    @Test
    void rejectsSupersededSchema(@TempDir final Path path) throws Exception {
        database(path.resolve("old-route"), null, "joint2-selection-v2", PAIR);
        assertThrows(IllegalStateException.class, () -> new CertificateStore(path.resolve("old-route")));
    }

    @Test
    void rejectsMissingFormatMarker(@TempDir final Path path) throws Exception {
        database(path.resolve("db"), null, false);
        assertThrows(IllegalStateException.class, () -> new CertificateStore(path.resolve("db")));
    }
}
