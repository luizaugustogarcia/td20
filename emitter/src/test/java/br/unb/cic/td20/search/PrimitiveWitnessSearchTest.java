package br.unb.cic.td20.search;

import br.unb.cic.td20.base.GPUSortingSearch;
import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.PrimitiveCertificate;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.model.PrimitivePair;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PrimitiveWitnessSearchTest {

    private static PrimitivePair pair() {
        return PrimitivePair.of(new int[] { 1, 2, 0, 4, 5, 3 }, new int[] { 0, 1, 2, 3, 4, 5 }).canonicalRepresentative();
    }

    private static GPUSortingSearch.PrimitiveSearchOutcome closure() {
        return new GPUSortingSearch.PrimitiveSearchOutcome(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                new int[] { 0 | (1 << 5) | (2 << 10) });
    }

    @Test
    void persistenceIsRequiredBeforeSearching() {
        assertThrows(IllegalStateException.class, () -> WitnessSearch.search(pair(), 2));
        assertThrows(IllegalStateException.class, () -> WitnessSearch.searchGpu(pair(), 2));
    }

    @Test
    void cpuSettlesBeforeGpuAndStoredResultsReplayInBulk(@TempDir final Path path) {
        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                val result = WitnessSearch.searchWithCpuPrefixTimeout(pair(),
                        2,
                        1000,
                        (input, budget) -> {
                            fail("CPU must settle this pair before GPU dispatch");
                            return null;
                        });
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertEquals(1, result.certificate().moveCount());
                CertificateValidator.validate(pair(), result.certificate(), 2);
                writer.flush();
                assertEquals(result.certificate(), writer.findPrimitiveResult(pair(), 2).orElseThrow());

                val legacy = writer.findResult(LegacyPairs.toLegacy(pair()), 2).orElseThrow();
                assertEquals(result.certificate().moveCount(), legacy.moves().size());
                assertTrue(writer.findPrimitiveResult(pair(), 1).isEmpty());

                val inputs = Collections.nCopies(4096, pair());
                val covered = WitnessSearch.lookupPrimitiveBatch(inputs, 2);
                assertEquals(inputs.size(), covered.length);
                for (final boolean hit : covered) {
                    assertTrue(hit);
                }
                for (final boolean hit : WitnessSearch.lookupPrimitiveBatch(inputs, 1)) {
                    assertFalse(hit);
                }

                assertEquals(result,
                        WitnessSearch.search(pair(), 2, (input, budget) -> {
                            fail("A stored result must avoid GPU work");
                            return null;
                        }));
                // Prepared bulk misses must not issue a second lookup.
                assertEquals(WitnessSearch.Disposition.RESIDUAL, WitnessSearch.searchCpuMiss(pair(), 2, 0).disposition());
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        }
    }

    @Test
    void timedCpuMissFallsThroughWithUntouchedFullObligationAndPersistsReplay(@TempDir final Path path) {
        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                val input = pair();
                val omega = input.omegaBytes();
                val beta = input.betaBytes();
                val dispatched = new AtomicInteger();
                val result = WitnessSearch.searchWithCpuPrefixTimeout(input,
                        2,
                        0,
                        (unchanged, budget) -> {
                            assertEquals(2, budget);
                            assertEquals(input, unchanged);
                            assertArrayEquals(omega, unchanged.omegaBytes());
                            assertArrayEquals(beta, unchanged.betaBytes());
                            dispatched.incrementAndGet();

                            return closure();
                        });
                assertEquals(1, dispatched.get());
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertArrayEquals(omega, input.omegaBytes());
                assertArrayEquals(beta, input.betaBytes());
                CertificateValidator.validate(input, result.certificate(), 2);
                writer.flush();
                assertEquals(result,
                        WitnessSearch.searchGpu(input, 2, (unused, budget) -> {
                            fail("Persisted GPU result must be reusable");
                            return null;
                        }));
            } finally {
                WitnessSearch.uninstallWriter(writer);
            }
        }
    }

    @Test
    void gpuTimeoutsAndInvalidWitnessesRemainUnsettled(@TempDir final Path path) {
        final String previous = System.getProperty("td20.reconstructionGpuMillis");
        System.setProperty("td20.reconstructionGpuMillis", "37");

        try (val writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);

            try {
                for (final int maximumMoves : new int[] { 9, 10, 11 }) {
                    val result = WitnessSearch.searchReconstructedGpu(pair(),
                            maximumMoves,
                            (input, budget, millis, depth) -> {
                                assertEquals(maximumMoves, budget);
                                assertEquals(maximumMoves, depth);
                                assertEquals(maximumMoves == 11 ? 0 : 37, millis);

                                return new GPUSortingSearch.PrimitiveSearchOutcome(maximumMoves == 11 ? GPUSortingSearch.Disposition.RESIDUAL : maximumMoves == 9 ? GPUSortingSearch.Disposition.TIMED_OUT : GPUSortingSearch.Disposition.DEPTH_LIMIT,
                                        new int[0]);
                            });
                    assertEquals(WitnessSearch.PrimitiveSearchResult.residual(), result);
                }

                assertThrows(IllegalArgumentException.class,
                        () -> WitnessSearch.searchGpu(pair(),
                                2,
                                (input, budget) -> new GPUSortingSearch.PrimitiveSearchOutcome(GPUSortingSearch.Disposition.COMPLETE, new int[0])));
                assertThrows(IllegalArgumentException.class,
                        () -> WitnessSearch.searchGpu(pair(),
                                2,
                                (input, budget) -> new GPUSortingSearch.PrimitiveSearchOutcome(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                                        new int[] { 0 | (2 << 5) | (1 << 10) })));
                writer.flush();
                assertTrue(writer.findPrimitiveResult(pair(), 11).isEmpty());
                writer.forEachObstruction(key -> fail("Search misses must not persist obstruction markers"));
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
    void searchResultsRejectConflictingDispositionAndCertificateKinds() {
        assertThrows(IllegalArgumentException.class,
                () -> new WitnessSearch.PrimitiveSearchResult(WitnessSearch.Disposition.RESIDUAL, PrimitiveCertificate.complete(new int[0])));
        assertThrows(IllegalArgumentException.class,
                () -> new WitnessSearch.PrimitiveSearchResult(WitnessSearch.Disposition.CERTIFICATE, PrimitiveCertificate.closure(new int[0])));
    }
}
