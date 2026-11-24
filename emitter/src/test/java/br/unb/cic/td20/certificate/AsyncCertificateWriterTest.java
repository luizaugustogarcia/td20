package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class AsyncCertificateWriterTest {

    private static CyclicTargetPair pair() {
        return CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
    }

    @Test
    void drainsUnderQueuePressureAndReopens(@TempDir final Path directory) throws Exception {
        final String previous = System.getProperty("td20.certificateQueueCapacity");
        System.setProperty("td20.certificateQueueCapacity", "2");

        try {
            try (val writer = new AsyncCertificateWriter(directory, 60);
                val pool = Executors.newFixedThreadPool(4)) {
                val tasks = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 100; i++) {
                    tasks.add(pool.submit(() -> writer.enqueue(pair(), List.of(Cycle.of(0, 1, 2)))));
                }
                for (val task : tasks) {
                    task.get();
                }

                writer.enqueueObstruction(pair(), 11);
            }

            try (val writer = new AsyncCertificateWriter(directory, 60)) {
                assertEquals(List.of(Cycle.of(0, 1, 2)), writer.find(pair(), 10).orElseThrow());

                val keys = new ArrayList<byte[]>();
                writer.forEachObstruction(keys::add);
                assertTrue(keys.isEmpty());
            }
        } finally {
            if (previous == null) {
                System.clearProperty("td20.certificateQueueCapacity");
            } else {
                System.setProperty("td20.certificateQueueCapacity", previous);
            }
        }
    }

    @Test
    void witnessRemovesAnUnresolvedKey(@TempDir final Path directory) {
        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueueObstruction(pair(), 11);
            writer.flush();

            val keys = new ArrayList<byte[]>();
            writer.forEachObstruction(keys::add);
            assertEquals(1, keys.size());
            assertTrue(writer.find(pair(), 10).isEmpty());
            writer.enqueue(pair(), List.of(Cycle.of(0, 1, 2)));
            writer.flush();
            keys.clear();
            writer.forEachObstruction(keys::add);
            assertTrue(keys.isEmpty());
        }
    }

    @Test
    void validLongerWitnessIsABudgetMiss(@TempDir final Path directory) {
        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            val moves = List.of(Cycle.of(0, 1, 2), Cycle.of(1, 0, 2), Cycle.of(0, 1, 2));
            writer.enqueue(pair(), moves);
            writer.flush();
            assertTrue(writer.find(pair(), 2).isEmpty());
            assertEquals(moves, writer.find(pair(), 3).orElseThrow());
        }
    }

    @Test
    void weakerRootClosureCannotReplaceAfterOneDischarge(@TempDir final Path directory) {
        val predecessor = CertificateCodec.pairForKey(java.util.HexFormat.of().parseHex("52c01118f08b1ae90c8543d2"), 19);
        val strong = br.unb.cic.td20.search.CpuPrefixSearch.search(predecessor, 9, 5, 1000000).orElseThrow().moves();
        val weak = List.of(Cycle.of(0, 10, 13));
        assertEquals(9, CertificateValidator.requiredBudget(predecessor, SortingCertificate.closure(strong)));
        assertEquals(10, CertificateValidator.requiredBudget(predecessor, SortingCertificate.closure(weak)));

        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueueClosure(predecessor, strong, 9);
            writer.flush();
            writer.enqueueClosure(predecessor, weak, 10);
            writer.flush();
            assertEquals(strong,
                    writer.find(predecessor, 9).orElseThrow(),
                    "A skipped inverse wave must retain its budget-9 discharge");
        }

        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            assertEquals(strong, writer.find(predecessor, 9).orElseThrow());
        }
    }

    @Test
    void duplicateCertificatesKeepTheStrongestBudgetWithAndWithoutMarkers(@TempDir final Path directory) {
        val strong = List.of(Cycle.of(0, 1, 2));
        val weak = List.of(Cycle.of(0, 1, 2), Cycle.of(1, 0, 2), Cycle.of(0, 1, 2));
        for (final boolean marker : List.of(false, true)) {
            try (val writer = new AsyncCertificateWriter(directory.resolve("proof-" + marker), 60)) {
                writer.enqueue(pair(), weak);
                writer.flush();
                if (marker) {
                    writer.enqueueObstruction(pair(), 1);
                    writer.flush();
                }

                writer.enqueue(pair(), strong);
                writer.enqueue(pair(), weak);
                writer.flush();
                assertEquals(strong, writer.find(pair(), 1).orElseThrow());
                writer.enqueue(pair(), weak);
                writer.flush();
                assertEquals(strong, writer.find(pair(), 1).orElseThrow());

                val markers = new ArrayList<byte[]>();
                writer.forEachObstruction(markers::add);
                assertTrue(markers.isEmpty());
            }
        }
    }

    @Test
    void multiGetPreservesMissingDuplicateAndOverBudgetPositions(@TempDir final Path directory) {
        val closure = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"), Cycle.of(0, 1, 2, 3, 4, 5));
        val missing = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2, 3, 4)), Cycle.of(0, 1, 2, 3, 4));
        val requests = List.of(missing, pair(), closure, pair(), missing);

        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueue(pair(), List.of(Cycle.of(0, 1, 2), Cycle.of(1, 0, 2), Cycle.of(0, 1, 2)));
            writer.enqueueClosure(closure, List.of(Cycle.of(0, 1, 2)), 2);
            writer.flush();
            assertTrue(writer.findResults(List.of(), 2).isEmpty());

            val results = writer.findResults(requests, 2);
            assertEquals(List.of(false, false, true, false, false),
                    results.stream().map(java.util.Optional::isPresent).toList());
            assertEquals(SortingCertificate.Kind.DIAMETER_CLOSURE, results.get(2).orElseThrow().kind());
            assertEquals(List.of(false, true, true, true, false),
                    writer.findResults(requests, 3).stream().map(java.util.Optional::isPresent).toList());
            for (int i = 0; i < requests.size(); i++) {
                assertEquals(writer.findResult(requests.get(i), 2), results.get(i));
            }
        }
    }

    @Test
    void multiGetRejectsCorruptCertificates(@TempDir final Path directory) throws Exception {
        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueue(pair(), List.of(Cycle.of(0, 1, 2)));
            writer.flush();
        }

        val handles = new ArrayList<org.rocksdb.ColumnFamilyHandle>();

        try (val options = new org.rocksdb.DBOptions();
            val familyOptions = new org.rocksdb.ColumnFamilyOptions();
            val database = RocksDB.open(options,
                    directory.toString(),
                    List.of(new org.rocksdb.ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions),
                            new org.rocksdb.ColumnFamilyDescriptor("obstructions".getBytes(java.nio.charset.StandardCharsets.UTF_8), familyOptions)),
                    handles)) {
            try (val writes = new org.rocksdb.WriteOptions().setDisableWAL(true);
                val flush = new org.rocksdb.FlushOptions().setWaitForFlush(true)) {
                database.put(handles.getFirst(), writes, CertificateCodec.keyFor(pair()), new byte[] { 42 });
                database.flush(flush, handles);
            } finally {
                handles.forEach(org.rocksdb.ColumnFamilyHandle::close);
            }
        }

        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            assertThrows(IllegalArgumentException.class, () -> writer.findResults(List.of(pair()), 10));
        }
    }

    @Test
    void closureRemovesObstructionAndReopensWithItsActualBudget(@TempDir final Path directory) {
        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueueObstruction(pair(), 11);
            writer.flush();
            writer.enqueueClosure(pair(), List.of(), 1);
            writer.flush();
            assertTrue(writer.find(pair(), 0).isEmpty());
            assertTrue(writer.find(pair(), 1).isPresent());

            val keys = new ArrayList<byte[]>();
            writer.forEachObstruction(keys::add);
            assertTrue(keys.isEmpty());
        }

        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            assertEquals(SortingCertificate.Kind.DIAMETER_CLOSURE, writer.findResult(pair(), 1).orElseThrow().kind());
            assertTrue(writer.find(pair(), 1).orElseThrow().isEmpty());
        }
    }

    @Test
    void rejectsSupersededJointRouteSchema(@TempDir final Path directory) throws Exception {
        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            writer.flush();
        }

        val handles = new ArrayList<org.rocksdb.ColumnFamilyHandle>();

        try (val options = new org.rocksdb.DBOptions();
            val familyOptions = new org.rocksdb.ColumnFamilyOptions();
            val database = RocksDB.open(options,
                    directory.toString(),
                    List.of(new org.rocksdb.ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions),
                            new org.rocksdb.ColumnFamilyDescriptor("obstructions".getBytes(java.nio.charset.StandardCharsets.UTF_8), familyOptions)),
                    handles)) {
            try (val writes = new org.rocksdb.WriteOptions().setDisableWAL(true);
                val flush = new org.rocksdb.FlushOptions().setWaitForFlush(true)) {
                database.put(handles.getFirst(),
                        writes,
                        "td20-schema".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "joint2-selection-v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                database.flush(flush, handles);
            } finally {
                handles.forEach(org.rocksdb.ColumnFamilyHandle::close);
            }
        }

        assertThrows(IllegalStateException.class, () -> new AsyncCertificateWriter(directory, 60));
    }

    @Test
    void rejectsUnversionedDatabaseAndInvalidWitness(@TempDir final Path directory) throws Exception {
        RocksDB.loadLibrary();

        try (val options = new Options().setCreateIfMissing(true);
            val ignored = RocksDB.open(options, directory.resolve("source").toString())) {
        }

        assertThrows(IllegalStateException.class, () -> new AsyncCertificateWriter(directory.resolve("source"), 60));

        try (val writer = new AsyncCertificateWriter(directory.resolve("proof"), 60)) {
            assertThrows(IllegalArgumentException.class, () -> writer.enqueue(pair(), List.of(Cycle.of(0, 2, 1))));
            assertThrows(IllegalArgumentException.class,
                    () -> writer.enqueueObstruction(pair(), br.unb.cic.td20.joint.KnownDiameters.MAX_BUDGET + 1));
        }
    }
}
