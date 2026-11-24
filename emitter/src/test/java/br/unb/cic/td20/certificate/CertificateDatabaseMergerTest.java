package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.*;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CertificateDatabaseMergerTest {

    private static CyclicTargetPair pair() {
        return CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
    }

    @Test
    void replayedUnionKeepsStrongestWitnessAndRecordsExactCounts(@TempDir final Path root) throws Exception {
        val strong = List.of(Cycle.of(0, 1, 2));
        val weak = List.of(Cycle.of(0, 1, 2), Cycle.of(1, 0, 2), Cycle.of(0, 1, 2));
        val closure = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"), Cycle.of(0, 1, 2, 3, 4, 5));

        try (val writer = new AsyncCertificateWriter(root.resolve("a"), 60)) {
            writer.enqueue(pair(), weak);
            writer.enqueueObstruction(closure, 2);
        }

        try (val writer = new AsyncCertificateWriter(root.resolve("b"), 60)) {
            writer.enqueue(pair(), strong);
            writer.enqueueClosure(closure, List.of(), 3);
        }

        val progress = new java.util.concurrent.CopyOnWriteArrayList<String>();
        val result = CertificateDatabaseMerger.merge(root.resolve("merged"), List.of(root.resolve("a"), root.resolve("b")), progress::add);
        assertEquals(new CertificateDatabaseMerger.Result(3, 2, 1), result);
        assertTrue(progress.stream().anyMatch(line -> line.contains("duplicates=1")));

        try (val writer = new AsyncCertificateWriter(root.resolve("merged"), 60)) {
            assertEquals(strong, writer.find(pair(), 1).orElseThrow());
            assertTrue(writer.findResult(closure, 3).isPresent());

            val markers = new ArrayList<byte[]>();
            writer.forEachObstruction(markers::add);
            assertTrue(markers.isEmpty());
        }

        assertTrue(Files.readString(root.resolve("merged/merge-v1.txt")).contains("uniqueWitnesses=2"));
        assertThrows(IllegalArgumentException.class,
                () -> CertificateDatabaseMerger.merge(root.resolve("merged"), List.of(root.resolve("a"))));
        assertThrows(Exception.class,
                () -> CertificateDatabaseMerger.merge(root.resolve("missing"), List.of(root.resolve("absent"))));
    }

    @Test
    void corruptDuplicateCannotHideBehindAValidWinner(@TempDir final Path root) throws Exception {
        for (final String name : List.of("good", "bad")) {
            try (val writer = new AsyncCertificateWriter(root.resolve(name), 60)) {
                writer.enqueue(pair(), List.of(Cycle.of(0, 1, 2)));
            }
        }

        val handles = new ArrayList<ColumnFamilyHandle>();

        try (val options = new DBOptions();
            val family = new ColumnFamilyOptions();
            val db = RocksDB.open(options,
                    root.resolve("bad").toString(),
                    List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, family),
                            new ColumnFamilyDescriptor(AsyncCertificateWriter.OBSTRUCTIONS, family)),
                    handles)) {
            try (val writes = new WriteOptions().setDisableWAL(true);
                val flush = new FlushOptions().setWaitForFlush(true)) {
                db.put(handles.getFirst(), writes, CertificateCodec.keyFor(pair()), new byte[] { 42 });
                db.flush(flush, handles);
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
            }
        }

        assertThrows(IllegalStateException.class,
                () -> CertificateDatabaseMerger.merge(root.resolve("output"), List.of(root.resolve("good"), root.resolve("bad"))));
        assertFalse(Files.exists(root.resolve("output/merge-v1.txt")));
    }

    @Test
    void appendPreservesPreparedWitnessesAndMarkersAndReplaysEveryNewWitness(@TempDir final Path root) throws Exception {
        val marker = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"), Cycle.of(0, 1, 2, 3, 4, 5));
        val identity = CyclicTargetPair.of(new MulticyclePermutation("(0)(1)(2)(3)"), Cycle.of(0, 1, 2, 3));
        final Path output = root.resolve("prepared"), input = root.resolve("roots");

        try (val writer = new AsyncCertificateWriter(output, 60)) {
            writer.enqueue(pair(), List.of(Cycle.of(0, 1, 2)));
            writer.enqueueObstruction(marker, 2);
        }

        try (val writer = new AsyncCertificateWriter(input, 60)) {
            writer.enqueue(identity, List.of());
        }

        assertEquals(new CertificateDatabaseMerger.Result(1, 1, 0),
                CertificateDatabaseMerger.append(output, List.of(input), line -> {
                }));

        try (val writer = new AsyncCertificateWriter(output, 60)) {
            assertTrue(writer.find(pair(), 1).isPresent());
            assertTrue(writer.find(identity, 0).isPresent());

            val markers = new ArrayList<byte[]>();
            writer.forEachObstruction(markers::add);
            assertEquals(1, markers.size());
            assertArrayEquals(CertificateCodec.keyFor(marker), markers.getFirst());
        }

        assertEquals(new CertificateDatabaseMerger.Result(1, 0, 0),
                CertificateDatabaseMerger.append(output, List.of(input), line -> {
                }),
                "Replaying an interrupted append must be idempotent");
        assertTrue(Files.readString(output.resolve("append-v1.txt")).startsWith("td20-certificate-append-v1\n"));
        assertThrows(IllegalArgumentException.class,
                () -> CertificateDatabaseMerger.append(output, List.of(output), line -> {
                }));
        assertThrows(IllegalArgumentException.class,
                () -> CertificateDatabaseMerger.append(root.resolve("missing"), List.of(input), line -> {
                }));
    }

    @Test
    void emptyAppendClosesAnEmptyResidualWaveAndPreservesThePreparedBase(@TempDir final Path root) throws Exception {
        final Path output = root.resolve("prepared");
        val marker = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"), Cycle.of(0, 1, 2, 3, 4, 5));

        try (val writer = new AsyncCertificateWriter(output, 60)) {
            writer.enqueue(pair(), List.of(Cycle.of(0, 1, 2)));
            writer.enqueueObstruction(marker, 2);
        }

        assertEquals(new CertificateDatabaseMerger.Result(0, 0, 0),
                CertificateDatabaseMerger.append(output, List.of(), line -> {
                }));

        try (val reader = new AsyncCertificateWriter(output, 60)) {
            assertTrue(reader.find(pair(), 1).isPresent());

            val markers = new ArrayList<byte[]>();
            reader.forEachObstruction(markers::add);
            assertEquals(1, markers.size());
            assertArrayEquals(CertificateCodec.keyFor(marker), markers.getFirst());
        }

        assertTrue(Files.readString(output.resolve("append-v1.txt")).contains("inputWitnesses=0"));
        assertThrows(IllegalArgumentException.class,
                () -> CertificateDatabaseMerger.append(root.resolve("absent"), List.of(), line -> {
                }));
        assertThrows(IllegalArgumentException.class,
                () -> CertificateDatabaseMerger.merge(root.resolve("fresh"), List.of()));
    }

    @Test
    void inferredDomainsDistinguishEqualByteLengths() {
        val random = new Random(414);
        for (int n = 1; n <= 21; n++) {
            final int[] beta = java.util.stream.IntStream.range(0, n).toArray();
            val order = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                order.add(i);
            }

            Collections.shuffle(order, random);

            val target = Cycle.of(order.stream().mapToInt(Integer::intValue).toArray());
            val pair = CyclicTargetPair.of(PermutationGroups.computeProduct(target, Cycle.of(beta).getInverse()), Cycle.of(beta));
            final byte[] key = CertificateCodec.keyFor(pair);
            assertEquals(n, CertificateCodec.pairForKey(key).getBeta().size());
            assertArrayEquals(key, CertificateCodec.keyFor(CertificateCodec.pairForKey(key)));
        }

        assertThrows(IllegalArgumentException.class, () -> CertificateCodec.pairForKey(new byte[] { (byte) 255 }));
    }

    @Test
    void equalBudgetTiesHaveTheSameEncodedWinnerInEitherInputOrder(@TempDir final Path root) throws Exception {
        try (val writer = new AsyncCertificateWriter(root.resolve("closure"), 60)) {
            writer.enqueueClosure(pair(), List.of(), 1);
        }

        try (val writer = new AsyncCertificateWriter(root.resolve("complete"), 60)) {
            writer.enqueue(pair(), List.of(Cycle.of(0, 1, 2)));
        }
        for (val names : List.of(List.of("closure", "complete"), List.of("complete", "closure"))) {
            final Path output = root.resolve("merged-" + names.getFirst());
            assertEquals(new CertificateDatabaseMerger.Result(2, 1, 0),
                    CertificateDatabaseMerger.merge(output, names.stream().map(root::resolve).toList()));

            try (val reader = new AsyncCertificateWriter(output, 60)) {
                assertEquals(SortingCertificate.Kind.COMPLETE, reader.findResult(pair(), 1).orElseThrow().kind());
            }
        }
    }
}
