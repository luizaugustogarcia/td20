package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.certificate.*;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.*;
import br.unb.cic.td20.search.CpuPrefixSearch;
import lombok.val;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ImportTd18AfterTwoTest {

    private final Map<String, String> properties = new HashMap<>();

    @BeforeEach
    void boundedTestPersistence() {
        for (val entry : Map.of("td20.certificateWriters",
                "2",
                "td20.certificateQueueCapacity",
                "64",
                "td20.reconstructionWriters",
                "2",
                "td20.reconstructionBufferKeys",
                "64",
                "td20.reconstructionQueueBatches",
                "16").entrySet()) {
            properties.put(entry.getKey(), System.getProperty(entry.getKey()));
            System.setProperty(entry.getKey(), entry.getValue());
        }
    }

    @AfterEach
    void restoreProperties() {
        properties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test
    void importsAndReplaysClosuresPreservesInformationalMarkersAndPublishesResiduals(@TempDir final Path root) throws Exception {
        val covered = covered();
        val missing = power(18, 4);
        val closure = CpuPrefixSearch.search(covered, 9, 5, 1000000).orElseThrow();
        assertEquals(SortingCertificate.Kind.DIAMETER_CLOSURE, closure.kind());
        sourceDatabase(root.resolve("td18"), Map.of(covered, CertificateCodec.resultFor(closure)), "joint-selection-v3");
        sourceStates(root.resolve("source"), List.of(covered, missing));

        val info = power(16, 2);

        try (val writer = new AsyncCertificateWriter(root.resolve("td20"), 60)) {
            writer.enqueueObstruction(info, 8);
            writer.flush();
        }

        val settings = settings(root, 2, 1, 1);
        val result = ImportTd18AfterTwo.run(settings, line -> {
        });
        assertEquals(new ImportTd18AfterTwo.Result(2, 1, 1, 0, 1), result);

        val checkpoint = ErikssonCheckpoint.read(settings.residualWork());
        assertEquals(ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION, checkpoint.mode());
        assertEquals(1, checkpoint.afterTwo());
        assertEquals(1, checkpoint.endpoints());

        try (val writer = new AsyncCertificateWriter(settings.database(), 60)) {
            val imported = writer.findResult(covered, 9).orElseThrow();
            assertEquals(closure, imported);
            assertTrue(CertificateValidator.requiredBudget(covered, imported) <= 9);
            assertTrue(writer.findResult(missing, 9).isEmpty());

            val markers = new ArrayList<byte[]>();
            writer.forEachObstruction(markers::add);
            assertEquals(2, markers.size());
            assertTrue(markers.stream().anyMatch(key -> Arrays.equals(key, CertificateCodec.keyFor(info))));
            assertTrue(markers.stream().anyMatch(key -> Arrays.equals(key, CertificateCodec.keyFor(missing))));
        }

        try (val residuals = DiskStateSet.openSealed(settings.residualWork().resolve("after-two"), 1)) {
            residuals.forEach(state -> {
                assertEquals(9, state.maximumMoves());
                assertEquals(missing.extendedToricClassKey(), state.pair().extendedToricClassKey());
            });
        }

        final String report = Files.readString(settings.residualWork().resolve(ImportTd18AfterTwo.REPORT));
        assertTrue(report.contains("importedCertificates=1"));
        assertTrue(report.contains("activeResiduals=1"));
        assertTrue(report.contains("replay=source-and-destination"));
        assertTrue(report.contains("status=complete"));
        assertThrows(IllegalArgumentException.class, () -> ImportTd18AfterTwo.run(settings, line -> {
        }));
    }

    @Test
    void validLongerResultsRemainResidualsAndAreNotImported(@TempDir final Path root) throws Exception {
        val longer = power(18, 4);
        val missing = power(18, 16);
        val result = SortingCertificate.closure(List.of());
        assertEquals(10, CertificateValidator.requiredBudget(longer, result));
        sourceDatabase(root.resolve("td18"), Map.of(longer, CertificateCodec.resultFor(result)), "joint-selection-v3");
        sourceStates(root.resolve("source"), List.of(longer, missing));

        val settings = settings(root, 2, 0, 2);
        assertEquals(new ImportTd18AfterTwo.Result(2, 0, 1, 1, 2), ImportTd18AfterTwo.run(settings, line -> {
        }));

        try (val writer = new AsyncCertificateWriter(settings.database(), 60)) {
            assertTrue(writer.findResult(longer, 11).isEmpty());

            val markers = new ArrayList<byte[]>();
            writer.forEachObstruction(markers::add);
            assertEquals(2, markers.size());
        }
    }

    @Test
    void primitiveImportPreservesLegacyWitnessBytesAndPartitionsTheFinalPartialBatch(@TempDir final Path root) throws Exception {
        val covered = covered();
        val longer = power(18, 4);
        val missing = power(18, 16);
        val primitiveCovered = LegacyPairs.fromLegacy(covered);
        val witness = CpuPrefixSearch.search(primitiveCovered, 9, 5, 1000000).orElseThrow();
        final byte[] expectedValue = CertificateCodec.resultFor(witness);
        val overBudget = PrimitiveCertificate.closure(new int[0]);
        sourceDatabase(root.resolve("td18"),
                Map.of(covered,
                        expectedValue,
                        longer,
                        CertificateCodec.resultFor(overBudget)),
                "joint-selection-v3");
        sourceStates(root.resolve("source"), List.of(covered, longer, missing));

        final String previousLookup = System.getProperty("td20.certificateLookupBatchSize");
        final String previousScan = System.getProperty("td20.reconstructionScanBatchKeys");
        System.setProperty("td20.certificateLookupBatchSize", "2");
        System.setProperty("td20.reconstructionScanBatchKeys", "2");

        try {
            val settings = settings(root, 3, 1, 2);
            assertEquals(new ImportTd18AfterTwo.Result(3, 1, 1, 1, 2), ImportTd18AfterTwo.run(settings, line -> {
            }));

            try (val writer = new AsyncCertificateWriter(settings.database(), 60)) {
                val actual = writer.findPrimitiveResult(primitiveCovered, 9).orElseThrow();
                assertEquals(witness, actual);
                assertArrayEquals(expectedValue, CertificateCodec.resultFor(actual));
                assertEquals(List.of(true, false, false),
                        writer.findPrimitiveResults(List.of(primitiveCovered, LegacyPairs.fromLegacy(longer), LegacyPairs.fromLegacy(missing)), 9).stream().map(Optional::isPresent).toList());
            }

            val residualKeys = new HashSet<String>();

            try (val states = DiskStateSet.openSealed(settings.residualWork().resolve("after-two"), 2)) {
                states.forEach(state -> {
                    assertEquals(18, state.primitivePair().size());
                    assertEquals(9, state.maximumMoves());
                    residualKeys.add(HexFormat.of().formatHex(CertificateCodec.keyFor(state.primitivePair())));
                });
            }

            assertEquals(Set.of(HexFormat.of().formatHex(CertificateCodec.keyFor(longer)),
                            HexFormat.of().formatHex(CertificateCodec.keyFor(missing))),
                    residualKeys);
        } finally {
            if (previousLookup == null) {
                System.clearProperty("td20.certificateLookupBatchSize");
            } else {
                System.setProperty("td20.certificateLookupBatchSize", previousLookup);
            }
            if (previousScan == null) {
                System.clearProperty("td20.reconstructionScanBatchKeys");
            } else {
                System.setProperty("td20.reconstructionScanBatchKeys", previousScan);
            }
        }
    }

    @Test
    void malformedWitnessesCannotBecomeMissingObligations(@TempDir final Path root) throws Exception {
        val pair = covered();
        sourceDatabase(root.resolve("td18"), Map.of(pair, new byte[] { 42 }), "joint-selection-v3");
        sourceStates(root.resolve("source"), List.of(pair));

        val settings = settings(root, 1, 0, 1);
        assertThrows(IllegalStateException.class, () -> ImportTd18AfterTwo.run(settings, line -> {
        }));
        assertFalse(Files.exists(settings.residualWork()));
    }

    @Test
    void wrongSchemasAndCheckpointsAreRejectedBeforePublication(@TempDir final Path root) throws Exception {
        val pair = covered();
        sourceDatabase(root.resolve("td18"), Map.of(), "wrong-route");
        sourceStates(root.resolve("source"), List.of(pair));

        val settings = settings(root, 1, 0, 1);
        assertThrows(IllegalStateException.class, () -> ImportTd18AfterTwo.run(settings, line -> {
        }));
        assertFalse(Files.exists(settings.database()));
        assertFalse(Files.exists(settings.residualWork()));
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO, 1, 1, -1, -1, ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION).write(settings.sourceWork());
        assertThrows(IllegalArgumentException.class, () -> ImportTd18AfterTwo.run(settings, line -> {
        }));
    }

    @Test
    void wrongDomainBudgetVersionAndNoncanonicalStatesCannotPublish(@TempDir final Path root) throws Exception {
        sourceDatabase(root.resolve("td18"), Map.of(), "joint-selection-v3");

        val base = covered();
        val images = new int[18];
        for (int x = 0; x < 18; x++) {
            images[(x + 1) % 18] = (base.getOmega().image(x) + 1) % 18;
        }

        val noncanonical = CertificateCodec.pairForKey(pack(images), 18);
        assertFalse(Arrays.equals(CertificateCodec.keyFor(noncanonical), CertificateCodec.keyFor(noncanonical.canonicalRepresentative())));

        val records = List.of(rawRecord(base, 10, 3),
                rawRecord(power(16, 2), 9, 3),
                rawRecord(base, 9, 2),
                rawRecord(noncanonical, 9, 3));
        for (int i = 0; i < records.size(); i++) {
            final Path work = Files.createDirectory(root.resolve("source-" + i));
            rawStates(work.resolve("after-two"), records.get(i));
            new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO, 1, 1, -1, -1).write(work);

            val settings = new ImportTd18AfterTwo.Settings(root.resolve("td18"), work, root.resolve("td20-" + i), root.resolve("residual-" + i), 1, 0, 1);
            assertThrows(RuntimeException.class, () -> ImportTd18AfterTwo.run(settings, line -> {
            }));
            assertFalse(Files.exists(settings.residualWork()));
        }
    }

    @Test
    void missingDataAndIncorrectPartitionCountsPreventCompletion(@TempDir final Path root) throws Exception {
        sourceDatabase(root.resolve("td18"), Map.of(), "joint-selection-v3");

        final Path missing = Files.createDirectory(root.resolve("source"));
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO, 1, 1, -1, -1).write(missing);

        val settings = settings(root, 1, 0, 1);
        assertThrows(IllegalStateException.class, () -> ImportTd18AfterTwo.run(settings, line -> {
        }));
        assertFalse(Files.exists(settings.residualWork()));

        try (val states = new DiskStateSet(missing.resolve("after-two"))) {
            states.add(covered(), 9);
            states.seal();
        }

        assertThrows(IllegalStateException.class, () -> ImportTd18AfterTwo.run(settings(root, 1, 1, 0), line -> {
        }));
        assertFalse(Files.exists(settings.residualWork()));
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO, 1, 2, -1, -1).write(missing);
        assertThrows(IllegalStateException.class, () -> ImportTd18AfterTwo.run(settings(root, 2, 0, 2), line -> {
        }));
        assertFalse(Files.exists(settings.residualWork()));
        assertThrows(IllegalArgumentException.class,
                () -> new ImportTd18AfterTwo.Settings(root.resolve("td18"), missing, root.resolve("out"), root.resolve("res"), 2, 0, 1));
    }

    private static ImportTd18AfterTwo.Settings settings(final Path root, final long source, final long imported, final long residual) {
        return new ImportTd18AfterTwo.Settings(root.resolve("td18"),
                root.resolve("source"),
                root.resolve("td20"),
                root.resolve("residual"),
                source,
                imported,
                residual);
    }

    private static CyclicTargetPair covered() {
        val omega = new MulticyclePermutation();
        for (int first = 0; first < 18; first += 3) {
            omega.add(Cycle.of(first, first + 1, first + 2));
        }

        return CyclicTargetPair.of(omega, Cycle.of(java.util.stream.IntStream.range(0, 18).toArray())).canonicalRepresentative();
    }

    private static CyclicTargetPair power(final int size, final int shift) {
        val images = new int[size];
        for (int x = 0; x < size; x++) {
            images[x] = (x + shift) % size;
        }

        return CertificateCodec.pairForKey(pack(images), size).canonicalRepresentative();
    }

    private static byte[] pack(final int[] images) {
        val result = new byte[(images.length * 5 + 7) / 8];
        for (int x = 0; x < images.length; x++) {
            for (int bit = 0; bit < 5; bit++) {
                if (((images[x] >>> (4 - bit)) & 1) != 0) {
                    result[(x * 5 + bit) / 8] |= (byte) (1 << (7 - (x * 5 + bit) % 8));
                }
            }
        }

        return result;
    }

    private static byte[] rawRecord(final CyclicTargetPair pair, final int budget, final int version) {
        final byte[] key = CertificateCodec.keyFor(pair);
        return ByteBuffer.allocate(key.length + 3).put((byte) version).put((byte) budget).put((byte) pair.getBeta().size()).put(key).array();
    }

    private static void sourceStates(final Path work, final List<CyclicTargetPair> pairs) throws Exception {
        Files.createDirectory(work);

        try (val states = new DiskStateSet(work.resolve("after-two"))) {
            for (val pair : pairs) {
                states.add(pair, 9);
            }

            assertEquals(pairs.size(), states.seal());
        }

        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO, 1, pairs.size(), -1, -1).write(work);
    }

    private static void rawStates(final Path directory, final byte[] key) throws Exception {
        RocksDB.loadLibrary();

        try (val options = new Options().setCreateIfMissing(true);
            val db = RocksDB.open(options, directory.toString());
            val writes = new WriteOptions().setDisableWAL(true);
            val flush = new FlushOptions().setWaitForFlush(true)) {
            db.put(writes, key, new byte[0]);
            db.flush(flush);
        }
    }

    private static void sourceDatabase(final Path directory,
            final Map<CyclicTargetPair, byte[]> values,
            final String schema) throws Exception {
        RocksDB.loadLibrary();

        val handles = new ArrayList<ColumnFamilyHandle>();

        try (val options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            val first = new ColumnFamilyOptions();
            val second = new ColumnFamilyOptions();
            val db = RocksDB.open(options,
                    directory.toString(),
                    List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, first),
                            new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), second)),
                    handles);
            val writes = new WriteOptions().setDisableWAL(true);
            val flush = new FlushOptions().setWaitForFlush(true)) {
            try {
                db.put(handles.getFirst(),
                        writes,
                        "td18-schema".getBytes(StandardCharsets.UTF_8),
                        schema.getBytes(StandardCharsets.UTF_8));
                for (val entry : values.entrySet()) {
                    db.put(handles.getFirst(), writes, CertificateCodec.keyFor(entry.getKey()), entry.getValue());
                }

                db.flush(flush, handles);
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
    }
}
