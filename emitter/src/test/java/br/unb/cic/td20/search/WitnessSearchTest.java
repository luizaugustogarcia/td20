package br.unb.cic.td20.search;

import br.unb.cic.td20.base.GPUSortingSearch;
import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WitnessSearchTest {

    private static CyclicTargetPair pair() {
        return CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"), Cycle.of(0, 1, 2, 3, 4, 5)).canonicalRepresentative();
    }

    @Test
    void sortingRequiresPersistenceBeforeAnyGpuWork() {
        assertThrows(IllegalStateException.class, () -> WitnessSearch.search(pair(), 2));
        assertThrows(IllegalStateException.class, () -> WitnessSearch.searchGpu(pair(), 2));
    }

    @Test
    void reconstructionDispatchesUncachedClassesWithoutAnyCpuPrefix(@TempDir final Path path) {
        final String previous = System.getProperty("td20.cpuPrefixDepth");
        System.setProperty("td20.cpuPrefixDepth", "-1");

        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                val dispatched = new java.util.concurrent.atomic.AtomicInteger();
                val result = WitnessSearch.searchGpu(pair(),
                        2,
                        (input, budget) -> {
                            assertEquals(2, budget);
                            assertEquals(pair().extendedToricClassKey(), input.extendedToricClassKey());
                            dispatched.incrementAndGet();

                            return new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                                    java.util.List.of(new int[] { 0, 1, 2 }));
                        });
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                writer.flush();
                assertEquals(result,
                        WitnessSearch.searchGpu(pair(),
                                2,
                                (input, budget) -> {
                                    fail("A stored certificate must avoid GPU dispatch");
                                    return null;
                                }));
                assertEquals(1, dispatched.get());
                assertTrue(writer.findResult(pair(), 2).isPresent());
                assertTrue(writer.findResult(pair(), 1).isEmpty());
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        } finally {
            if (previous == null) {
                System.clearProperty("td20.cpuPrefixDepth");
            } else {
                System.setProperty("td20.cpuPrefixDepth", previous);
            }
        }
    }

    @Test
    void gpuResidualsAndInvalidCertificatesCannotBecomeDischarges(@TempDir final Path path) {
        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                assertEquals(WitnessSearch.Disposition.RESIDUAL,
                        WitnessSearch.searchGpu(pair(),
                                2,
                                (input, budget) -> new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.RESIDUAL, java.util.List.of())).disposition());
                assertThrows(IllegalArgumentException.class,
                        () -> WitnessSearch.searchGpu(pair(),
                                2,
                                (input, budget) -> new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.COMPLETE, java.util.List.of())));
                writer.flush();
                assertTrue(writer.findResult(pair(), 2).isEmpty());
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        }
    }

    @Test
    void reconstructionTimeoutsPreserveObligationBudgetsAndNeverPersistWitnesses(@TempDir final Path path) {
        final String previous = System.getProperty("td20.reconstructionGpuMillis");
        System.setProperty("td20.reconstructionGpuMillis", "37");

        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                val dispatched = new java.util.concurrent.atomic.AtomicInteger();
                for (final int maximumMoves : new int[] { 9, 10, 11 }) {
                    val result = WitnessSearch.searchReconstructedGpu(pair(),
                            maximumMoves,
                            (input, budget, timeout, depth) -> {
                                assertEquals(maximumMoves, budget);
                                assertEquals(maximumMoves, depth);
                                assertEquals(maximumMoves == 11 ? 0 : 37, timeout);
                                assertEquals(pair().extendedToricClassKey(), input.extendedToricClassKey());
                                dispatched.incrementAndGet();

                                return new GPUSortingSearch.SearchOutcome(maximumMoves == 11 ? GPUSortingSearch.Disposition.RESIDUAL : maximumMoves == 9 ? GPUSortingSearch.Disposition.TIMED_OUT : GPUSortingSearch.Disposition.DEPTH_LIMIT,
                                        java.util.List.of());
                            });
                    assertEquals(WitnessSearch.Disposition.RESIDUAL, result.disposition());
                    assertTrue(result.moves().isEmpty());
                }

                assertEquals(3, dispatched.get());
                writer.flush();
                assertTrue(writer.findResult(pair(), 11).isEmpty());
                writer.forEachObstruction(key -> fail("An incomplete search cannot record a certificate or a proven failure"));
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        } finally {
            if (previous == null) {
                System.clearProperty("td20.reconstructionGpuMillis");
            } else {
                System.setProperty("td20.reconstructionGpuMillis", previous);
            }
        }
    }

    @Test
    void reconstructionTimeoutDefaultsAreValidatedAndFinalBudgetAlwaysHasNoCutoff() {
        final String previous = System.getProperty("td20.reconstructionGpuMillis");

        try {
            System.clearProperty("td20.reconstructionGpuMillis");
            assertEquals(0, WitnessSearch.reconstructionGpuMillis(9));
            assertEquals(0, WitnessSearch.reconstructionGpuMillis(10));
            assertEquals(0, WitnessSearch.reconstructionGpuMillis(11));
            System.setProperty("td20.reconstructionGpuMillis", "0");
            assertEquals(0, WitnessSearch.reconstructionGpuMillis(10));
            for (final String invalid : new String[] { "-1", "invalid", Long.toString(Long.MAX_VALUE) }) {
                System.setProperty("td20.reconstructionGpuMillis", invalid);
                assertThrows(IllegalArgumentException.class, () -> WitnessSearch.reconstructionGpuMillis(10));
                assertEquals(0, WitnessSearch.reconstructionGpuMillis(11));
            }
        } finally {
            if (previous == null) {
                System.clearProperty("td20.reconstructionGpuMillis");
            } else {
                System.setProperty("td20.reconstructionGpuMillis", previous);
            }
        }
    }

    @Test
    void advisoryGpuSlicesPreserveFullDepthAndDoNotChangeFinalSearchLimits(@TempDir final Path path) {
        final String previous = System.getProperty("td20.rootPrefetchGpuMillis");
        final String previousCpu = System.getProperty("td20.cpuPrefixDepth");
        System.clearProperty("td20.rootPrefetchGpuMillis");
        System.setProperty("td20.cpuPrefixDepth", "-1");

        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                val result = WitnessSearch.searchGpuPrefetch(pair(),
                        11,
                        (input, budget, timeout, depth) -> {
                            assertEquals(11, budget);
                            assertEquals(11, depth);
                            assertEquals(100, timeout);

                            return new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.TIMED_OUT, java.util.List.of());
                        });
                assertEquals(WitnessSearch.Disposition.RESIDUAL, result.disposition());
                WitnessSearch.searchReconstructedGpu(pair(),
                        11,
                        (input, budget, timeout, depth) -> {
                            assertEquals(11, budget);
                            assertEquals(11, depth);
                            assertEquals(0, timeout);

                            return new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.RESIDUAL, java.util.List.of());
                        });
                writer.flush();
                assertTrue(writer.findResult(pair(), 11).isEmpty());
                writer.forEachObstruction(key -> fail("An advisory timeout cannot persist an obstruction"));
                for (final String invalid : new String[] { "0", "-1", "invalid", Long.toString(Long.MAX_VALUE) }) {
                    System.setProperty("td20.rootPrefetchGpuMillis", invalid);
                    assertThrows(IllegalArgumentException.class,
                            () -> WitnessSearch.searchGpuPrefetch(pair(),
                                    11,
                                    (input, budget, timeout, depth) -> {
                                        fail("Invalid time slice must not dispatch");
                                        return null;
                                    }));
                }
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        } finally {
            if (previous == null) {
                System.clearProperty("td20.rootPrefetchGpuMillis");
            } else {
                System.setProperty("td20.rootPrefetchGpuMillis", previous);
            }
            if (previousCpu == null) {
                System.clearProperty("td20.cpuPrefixDepth");
            } else {
                System.setProperty("td20.cpuPrefixDepth", previousCpu);
            }
        }
    }

    @Test
    void reconstructionAlwaysUsesFullDepthEvenWithAnOldDepthCapProperty() {
        final String previous = System.getProperty("td20.reconstructionGpuDepth");

        try {
            System.clearProperty("td20.reconstructionGpuDepth");
            assertEquals(9, WitnessSearch.reconstructionGpuDepth(9));
            assertEquals(10, WitnessSearch.reconstructionGpuDepth(10));
            assertEquals(3, WitnessSearch.reconstructionGpuDepth(3));
            assertEquals(11, WitnessSearch.reconstructionGpuDepth(11));
            System.setProperty("td20.reconstructionGpuDepth", "2");
            assertEquals(10, WitnessSearch.reconstructionGpuDepth(10));
            assertEquals(11, WitnessSearch.reconstructionGpuDepth(11));
            for (final String invalid : new String[] { "0", "-1", "12", "invalid" }) {
                System.setProperty("td20.reconstructionGpuDepth", invalid);
                assertEquals(10, WitnessSearch.reconstructionGpuDepth(10));
                assertEquals(11, WitnessSearch.reconstructionGpuDepth(11));
            }
        } finally {
            if (previous == null) {
                System.clearProperty("td20.reconstructionGpuDepth");
            } else {
                System.setProperty("td20.reconstructionGpuDepth", previous);
            }
        }
    }

    @Test
    void preparedGpuMissStillReplaysAndPersistsTheActualWitness(@TempDir final Path path) {
        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                val result = WitnessSearch.searchGpuPrefetch(pair(),
                        2,
                        (input, budget, timeout, depth) -> new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                                java.util.List.of(new int[] { 0, 1, 2 })),
                        false);
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                writer.flush();
                assertTrue(writer.findResult(pair(), 2).isPresent());
                assertThrows(IllegalArgumentException.class,
                        () -> WitnessSearch.searchGpuPrefetch(pair(),
                                2,
                                (input, budget, timeout, depth) -> new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.COMPLETE, java.util.List.of()),
                                false));
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        }
    }

    @Test
    void interruptedCpuSearchStopsWithoutReturningACertificate() {
        Thread.currentThread().interrupt();

        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> CpuPrefixSearch.search(pair(), 2, 10, 16777216));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void largeBatchReplaysEveryHitBeforeDispatch(@TempDir final Path path) {
        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                writer.enqueueClosure(pair(), java.util.List.of(Cycle.of(0, 1, 2)), 2);
                writer.flush();

                val batch = java.util.Collections.nCopies(4096, pair());
                val hits = WitnessSearch.lookupBatch(batch, 2);
                assertEquals(4096, hits.length);
                for (final boolean hit : hits) {
                    assertTrue(hit);
                }
                for (final boolean hit : WitnessSearch.lookupBatch(batch, 1)) {
                    assertFalse(hit);
                }
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        }
    }

    @Test
    void timedCpuPrefixEitherSettlesOrFallsThroughWithTheFullBudget(@TempDir final Path path) {
        try (val writer = new AsyncCertificateWriter(path.resolve("settled"), 60)) {
            WitnessSearch.installWriter(writer);

            try {
                val result = WitnessSearch.searchWithCpuPrefixTimeout(pair(),
                        2,
                        1000,
                        (input, budget) -> {
                            fail("The CPU prefix should settle this pair");
                            return null;
                        });
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertEquals(1, result.moves().size());
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        }

        try (val writer = new AsyncCertificateWriter(path.resolve("timeout"), 60)) {
            WitnessSearch.installWriter(writer);

            try {
                val dispatched = new java.util.concurrent.atomic.AtomicInteger();
                val result = WitnessSearch.searchWithCpuPrefixTimeout(pair(),
                        2,
                        0,
                        (input, budget) -> {
                            assertEquals(2, budget);
                            assertEquals(pair().extendedToricClassKey(), input.extendedToricClassKey());
                            dispatched.incrementAndGet();

                            return new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                                    java.util.List.of(new int[] { 0, 1, 2 }));
                        });
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertEquals(1, dispatched.get());
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        }

        assertThrows(IllegalArgumentException.class,
                () -> WitnessSearch.searchWithCpuPrefixTimeout(pair(), 2, -1, (input, budget) -> null));
    }

    @Test
    void cpuStagePersistsClosuresAndLeavesMissesForTheGpuWithoutObstructionMarkers(@TempDir final Path path) {
        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                assertEquals(WitnessSearch.Disposition.RESIDUAL, WitnessSearch.searchCpu(pair(), 2, 0).disposition());
                writer.flush();
                assertTrue(writer.findResult(pair(), 2).isEmpty());
                writer.forEachObstruction(key -> fail("A heuristic CPU miss is not a final discharge miss"));
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, WitnessSearch.searchCpu(pair(), 2, 1000).disposition());
                writer.flush();
                assertEquals(1, writer.findResult(pair(), 2).orElseThrow().moves().size());
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, WitnessSearch.searchCpu(pair(), 2, 0).disposition());
                assertThrows(IllegalArgumentException.class, () -> WitnessSearch.searchCpu(pair(), 2, -1));
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        }
    }

    @Test
    void reconstructionDefaultsToFivePrefixMovesAndHonorsAnExplicitDepth(@TempDir final Path path) {
        val input = CyclicTargetPair.of(new MulticyclePermutation("(0 1 11 6 8 12 9 3 5 2 10 7 4)"),
                Cycle.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)).canonicalRepresentative();
        final String previous = System.getProperty("td20.cpuPrefixDepth");

        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                System.setProperty("td20.cpuPrefixDepth", "4");
                assertEquals(WitnessSearch.Disposition.RESIDUAL, WitnessSearch.searchCpu(input, 6, 1000).disposition());
                System.clearProperty("td20.cpuPrefixDepth");
                assertTrue(CpuPrefixSearch.search(input, 6).isEmpty());

                val result = WitnessSearch.searchCpu(input, 6, 1000);
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertEquals(5, result.moves().size());
                writer.flush();

                val stored = writer.findResult(input, 6).orElseThrow();
                assertEquals(result.moves(), stored.moves());
                CertificateValidator.validate(input, stored, 6);
                assertTrue(writer.findResult(input, 5).isEmpty());
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        } finally {
            if (previous == null) {
                System.clearProperty("td20.cpuPrefixDepth");
            } else {
                System.setProperty("td20.cpuPrefixDepth", previous);
            }
        }
    }

    @Test
    void generalTimeoutFallsThroughAndReusesCheckedCertificates(@TempDir final Path path) {
        final String previous = System.getProperty("td20.cpuPrefixMillis");
        System.setProperty("td20.cpuPrefixMillis", "0");

        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                val input = pair();
                final String before = input.toString();
                val result = WitnessSearch.search(input,
                        2,
                        (unchanged, budget) -> {
                            assertEquals(2, budget);
                            assertEquals(input.extendedToricClassKey(), unchanged.extendedToricClassKey());

                            return new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                                    java.util.List.of(new int[] { 0, 1, 2 }));
                        });
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertEquals(before, input.toString());
                writer.flush();
                assertEquals(result,
                        WitnessSearch.search(input,
                                2,
                                (unused, budget) -> {
                                    fail("A checked stored result must avoid CPU and GPU searches");
                                    return null;
                                }));
                System.setProperty("td20.cpuPrefixMillis", "-1");
                assertThrows(IllegalArgumentException.class, () -> CpuPrefixSearch.search(input, 2));
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        } finally {
            if (previous == null) {
                System.clearProperty("td20.cpuPrefixMillis");
            } else {
                System.setProperty("td20.cpuPrefixMillis", previous);
            }
        }
    }

    @Test
    void cpuClosureIsPersistedReplayedAndNotACompleteWitness(@TempDir final Path path) {
        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                val result = WitnessSearch.search(pair(), 2);
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertEquals(1, result.moves().size());
                assertThrows(IllegalArgumentException.class, () -> CertificateValidator.validate(pair(), result.moves(), 2));
                writer.flush();
                assertTrue(writer.find(pair(), 1).isEmpty());
                assertEquals(result.moves(), writer.find(pair(), 2).orElseThrow());
                assertEquals(result, WitnessSearch.search(pair(), 2));
            } finally {
                WitnessSearch.uninstallWriter(writer);
                WitnessSearch.shutdownGpuStats();
            }
        }
    }
}
