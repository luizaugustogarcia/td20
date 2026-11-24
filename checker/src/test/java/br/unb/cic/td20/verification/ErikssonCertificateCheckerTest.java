package br.unb.cic.td20.verification;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.certificate.CertificateStore;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.joint.CyclicTargetMoves;
import br.unb.cic.td20.joint.ErikssonInverseReconstruction;
import br.unb.cic.td20.joint.FinalInverseCover;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.ExtendedToricClassKey;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ErikssonCertificateCheckerTest {

    @Test
    void successfulInverseCoverageReclaimsEveryCompletedScratchWave(@TempDir final Path path) throws Exception {
        val database = path.resolve("certificates");
        emptyDatabase(database);

        val work = Files.createDirectory(path.resolve("work"));

        try (val certificates = new CertificateStore(database);
            val endpoints = new CheckerStateSet(work.resolve("endpoint-residuals"));
            val progress = new VerificationProgress()) {
            val coverage = ErikssonCertificateChecker.verifyAvoidance(certificates, endpoints, work, progress);
            assertEquals(0, coverage.endpoints());
            assertEquals(0, coverage.roots());
            for (final String wave : List.of("endpoint-residuals", "after-two", "after-two-residuals", "after-one", "roots")) {
                assertFalse(Files.exists(work.resolve(wave)), "Completed scratch remains: " + wave);
            }

            assertTrue(Files.isRegularFile(work.resolve("inverse-coverage-v3.txt")));
            assertTrue(Files.isRegularFile(database.resolve("CURRENT")));
        }
    }

    @Test
    void failedSourceReplayPreservesItsScratchAndPublishesNoCoverage(@TempDir final Path path) throws Exception {
        val database = path.resolve("certificates");
        emptyDatabase(database);

        val work = Files.createDirectory(path.resolve("work"));

        try (val certificates = new CertificateStore(database);
            val endpoints = new CheckerStateSet(work.resolve("endpoint-residuals"));
            val progress = new VerificationProgress()) {
            // A fixed-point-free pair from the wrong domain must abort before handoff.
            endpoints.add(new SearchState(8, pairWithOrientedThreeCycles(18)));
            assertThrows(IllegalStateException.class,
                    () -> ErikssonCertificateChecker.verifyAvoidance(certificates, endpoints, work, progress));
            assertTrue(Files.isRegularFile(work.resolve("endpoint-residuals/CURRENT")));
            assertFalse(Files.exists(work.resolve("inverse-coverage-v3.txt")));
        }
    }

    @Test
    void missingEndpointWitnessRegeneratesAvoidanceWithoutAnyObstructionMarker(@TempDir final Path path) throws Exception {
        val directory = path.resolve("certificate");
        emptyDatabase(directory);

        val endpoint = new br.unb.cic.td20.joint.ErikssonInventory.Endpoint(1, new int[] { 3, 2, 2 }, new boolean[3]);
        final long expected = br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator.countRepresentativesByBurnside(endpoint.partition(), endpoint.orientedByPart());

        try (val store = new CertificateStore(directory);
            val residuals = new CheckerStateSet(path.resolve("regenerated"));
            val progress = new VerificationProgress()) {
            ErikssonCertificateChecker.verifyEndpoint(endpoint, store, residuals, progress);
            assertTrue(expected > 0);
            assertEquals(endpoint.elementaryClosure() ? 0 : expected, residuals.seal().count());
        }
    }

    @Test
    void partialRangesCannotBeReportedAsComplete() throws Exception {
        val options = ErikssonCertificateChecker.Options.parse(new String[] { "--structural-only", "--phase=all", "--partitions=2-" + br.unb.cic.td20.joint.ErikssonRoute.ROOT_PARTITIONS },
                Path.of("."));
        assertFalse(options.completeScope());
        assertThrows(IllegalArgumentException.class,
                () -> ErikssonCertificateChecker.Options.parse(new String[] { "--structural-only", "--endpoints=0-1" }, Path.of(".")));
    }

    @Test
    void missingDirectRootIsRejectedWithoutAMarker(@TempDir final Path path) throws Exception {
        final Path directory = path.resolve("empty-root-database");
        emptyDatabase(directory);

        final int[] partition = { 7 };
        val counts = br.unb.cic.td20.joint.ErikssonRootGenerator.count(partition, 2, new java.util.concurrent.atomic.LongAdder());
        assertTrue(counts.direct() > 0);

        val row = new br.unb.cic.td20.joint.ErikssonInventory.Root(1, partition, counts.unoriented(), counts.oriented(), counts.supportCleared());

        try (val store = new CertificateStore(directory);
            val progress = new VerificationProgress()) {
            assertThrows(IllegalStateException.class, () -> ErikssonCertificateChecker.verifyRoot(row, store, progress));
        }
    }

    @Test
    void budgetNineResidualsDependOnReplayedWitnessesNeverMarkers(@TempDir final Path path) throws Exception {
        val pair = pairWithOrientedThreeCycles(18);
        val result = threeCycleClosure(pair, 1);
        assertEquals(9, CertificateValidator.requiredBudget(pair, result));
        for (final boolean witness : new boolean[] { false, true }) {
            for (final boolean marker : new boolean[] { false, true }) {
                final Path scope = Files.createDirectory(path.resolve("witness-" + witness + "-marker-" + marker));
                final Path database = scope.resolve("certificates");
                emptyDatabase(database);
                put(database, pair, witness ? CertificateCodec.resultFor(result) : null, marker);

                try (val certificates = new CertificateStore(database);
                    val afterTwo = new CheckerStateSet(scope.resolve("after-two"));
                    val residuals = new CheckerStateSet(scope.resolve("residuals"))) {
                    val state = new SearchState(9, pair);
                    afterTwo.add(state);
                    assertEquals(1, afterTwo.seal().count());
                    assertEquals(marker, certificates.hasObstruction(pair));

                    val inventory = ErikssonCertificateChecker.retainAfterTwoResiduals(certificates, afterTwo, residuals);
                    assertEquals(witness ? 1 : 0, inventory.closed());
                    assertEquals(witness ? 0 : 1, inventory.residual());
                    assertEquals(!witness, residuals.contains(state));
                }
            }
        }
    }

    @Test
    void invalidImportedWitnessIsAnErrorNotAResidual(@TempDir final Path path) throws Exception {
        val pair = pairWithOrientedThreeCycles(18);
        final Path database = path.resolve("certificates");
        emptyDatabase(database);
        put(database, pair, CertificateCodec.resultFor(SortingCertificate.complete(List.of())), false);

        try (val certificates = new CertificateStore(database);
            val afterTwo = new CheckerStateSet(path.resolve("after-two"));
            val residuals = new CheckerStateSet(path.resolve("residuals"))) {
            afterTwo.add(new SearchState(9, pair));
            afterTwo.seal();

            val failure = assertThrows(IllegalStateException.class,
                    () -> ErikssonCertificateChecker.retainAfterTwoResiduals(certificates, afterTwo, residuals));
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }

            assertInstanceOf(IllegalArgumentException.class, cause);
            assertTrue(cause.getMessage().contains("does not sort"));
        }
    }

    @Test
    void budgetTenWitnessesDoNotSuppressEitherFinalInverseBranch(@TempDir final Path path) throws Exception {
        val twenty = pairWithOrientedThreeCycles(20);
        val twentyOne = pairWithOrientedThreeCycles(21);
        val twentyResult = threeCycleClosure(twenty, 1);
        val twentyOneResult = threeCycleClosure(twentyOne, 2);
        assertEquals(10, CertificateValidator.requiredBudget(twenty, twentyResult));
        assertEquals(10, CertificateValidator.requiredBudget(twentyOne, twentyOneResult));

        final Path database = path.resolve("certificates");
        emptyDatabase(database);
        put(database, twenty, CertificateCodec.resultFor(twentyResult), false);
        put(database, twentyOne, CertificateCodec.resultFor(twentyOneResult), false);

        final Set<ExtendedToricClassKey> expected = ConcurrentHashMap.newKeySet(), actual = ConcurrentHashMap.newKeySet();
        ErikssonInverseReconstruction.preimages(twenty,
                1,
                parent -> {
                    if (!immediate(parent)) {
                        expected.add(parent.extendedToricClassKey());
                    }
                });
        ErikssonInverseReconstruction.preimages(twentyOne,
                0,
                parent -> {
                    if (!immediate(parent)) {
                        expected.add(parent.extendedToricClassKey());
                    }
                });

        try (val certificates = new CertificateStore(database);
            val afterOne = new CheckerStateSet(path.resolve("after-one"));
            val roots = new CheckerStateSet(path.resolve("roots"))) {
            assertTrue(certificates.find(twenty, 10).isPresent());
            assertTrue(certificates.find(twentyOne, 10).isPresent());
            afterOne.add(new SearchState(10, twenty));
            afterOne.add(new SearchState(10, twentyOne));
            assertEquals(2, afterOne.seal().count());

            val cover = ErikssonCertificateChecker.expandFirstMovePreimages(afterOne, roots);
            assertEquals(2, cover.sources());
            assertEquals(4, cover.sourceSides());
            assertEquals(7600 + 2660, cover.candidateSlots());
            assertEquals(expected.size(), roots.seal().count());
            roots.forEachConcurrently(2,
                    state -> {
                        assertEquals(21, state.pair().size());
                        assertEquals(11, state.maximumMoves());
                        actual.add(br.unb.cic.td20.compat.LegacyPairs.toLegacy(state.pair()).extendedToricClassKey());
                    });
            assertEquals(expected, actual);
            if (expected.isEmpty()) {
                ErikssonCertificateChecker.verifyFinalRoots(certificates, roots);
            } else {
                assertThrows(IllegalStateException.class, () -> ErikssonCertificateChecker.verifyFinalRoots(certificates, roots));
            }
        }
    }

    @Test
    void anUnclosedFinalRootStillRequiresItsDatabaseWitnessWithoutAMarker(@TempDir final Path path) throws Exception {
        val omega = new MulticyclePermutation();
        val cycle = new int[21];
        for (int i = 0; i < 21; i++) {
            cycle[i] = 19 * i % 21;
        }

        omega.add(Cycle.of(cycle));

        val root = CyclicTargetPair.ofOwned(omega, Cycle.of(IntStream.range(0, 21).toArray()));
        assertFalse(immediate(root));

        final Path database = path.resolve("certificates");
        emptyDatabase(database);

        try (val certificates = new CertificateStore(database);
            val roots = new CheckerStateSet(path.resolve("roots"))) {
            roots.add(new SearchState(11, root));
            assertEquals(1, roots.seal().count());
            assertFalse(certificates.hasObstruction(root));
            assertThrows(IllegalStateException.class, () -> ErikssonCertificateChecker.verifyFinalRoots(certificates, roots));
        }
    }

    @Test
    void emptyCoverReportHasExplicitResidualSemanticsAndCannotOmitSources(@TempDir final Path path) throws Exception {
        val empty = FinalInverseCover.Counts.empty();
        val report = new ErikssonCertificateChecker.InverseCoverage(0, 0, 0, 0, 0, 0, empty);
        ErikssonCertificateChecker.publishInverseCoverage(path, report);

        val lines = Files.readAllLines(path.resolve("inverse-coverage-v3.txt"));
        assertEquals("td20-independent-inverse-coverage-v3", lines.getFirst());
        assertTrue(lines.contains("rootInventory=ELEMENTARY_RESIDUALS"));
        assertTrue(lines.contains("budgetTenLookup=false"));
        assertTrue(lines.contains("candidateSlots=0"));
        assertTrue(lines.contains("residualOccurrences=0"));
        assertThrows(IllegalArgumentException.class,
                () -> new ErikssonCertificateChecker.InverseCoverage(0, 0, 0, 0, 1, 0, empty));
        assertThrows(IllegalArgumentException.class,
                () -> new ErikssonCertificateChecker.InverseCoverage(0, 0, 0, 0, 0, 1, empty));
    }

    private static boolean immediate(final CyclicTargetPair pair) {
        final int n = pair.getBeta().size();
        final int[] original = CyclicTargetMoves.images(pair), inverse = new int[n];
        for (int i = 0; i < n; i++) {
            inverse[original[i]] = i;
        }
        for (int side = 0; side < 2; side++) {
            final int[] images = side == 0 ? original : inverse;
            val order = side == 0 ? pair.getBeta() : pair.getRho();
            for (int a = 0; a < n - 2; a++) {
                for (int b = a + 1; b < n - 1; b++) {
                    for (int c = b + 1; c < n; c++) {
                        if (CyclicTargetMoves.fixed(CyclicTargetMoves.apply(images, order.get(a), order.get(b), order.get(c))) >= 2) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private static CyclicTargetPair pairWithOrientedThreeCycles(final int symbols) {
        val omega = new MulticyclePermutation();
        int cursor = 0;
        final int shortCycles = symbols == 21 ? 2 : 1;
        for (int i = 0; i < shortCycles; i++) {
            omega.add(Cycle.of(cursor, cursor + 1, cursor + 2));
            cursor += 3;
        }

        omega.add(Cycle.of(IntStream.range(cursor, symbols).toArray()));

        return CyclicTargetPair.ofOwned(omega, Cycle.of(IntStream.range(0, symbols).toArray())).canonicalRepresentative();
    }

    private static SortingCertificate threeCycleClosure(final CyclicTargetPair pair, final int cycles) {
        val moves = pair.getOmega().stream().filter(cycle -> cycle.size() == 3).limit(cycles).map(cycle -> Cycle.of(Arrays.stream(cycle.getSymbols()).sorted().toArray())).toList();
        assertEquals(cycles, moves.size());

        return SortingCertificate.closure(moves);
    }

    private static void put(final Path directory, final CyclicTargetPair pair, final byte[] result, final boolean marker) throws Exception {
        try (val options = new DBOptions();
            val first = new ColumnFamilyOptions();
            val second = new ColumnFamilyOptions()) {
            val handles = new ArrayList<ColumnFamilyHandle>();
            val database = RocksDB.open(options,
                    directory.toString(),
                    List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, first),
                            new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), second)),
                    handles);

            try (val writes = new WriteOptions().setDisableWAL(true);
                val flush = new FlushOptions().setWaitForFlush(true)) {
                final byte[] key = CertificateCodec.canonicalKeyFor(pair);
                if (result != null) {
                    database.put(handles.get(0), writes, key, result);
                }
                if (marker) {
                    database.put(handles.get(1), writes, key, new byte[0]);
                }

                database.flush(flush, handles);
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
                database.close();
            }
        }
    }

    private static void emptyDatabase(final Path directory) throws Exception {
        RocksDB.loadLibrary();

        try (val options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            val first = new ColumnFamilyOptions();
            val second = new ColumnFamilyOptions()) {
            val handles = new ArrayList<ColumnFamilyHandle>();

            try (val database = RocksDB.open(options,
                    directory.toString(),
                    List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, first),
                            new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), second)),
                    handles);
                val writes = new WriteOptions().setDisableWAL(true);
                val flush = new FlushOptions().setWaitForFlush(true)) {
                database.put(handles.get(0),
                        writes,
                        "td20-schema".getBytes(StandardCharsets.UTF_8),
                        "eriksson-three-v1".getBytes(StandardCharsets.UTF_8));
                database.flush(flush, handles);
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
    }
}
