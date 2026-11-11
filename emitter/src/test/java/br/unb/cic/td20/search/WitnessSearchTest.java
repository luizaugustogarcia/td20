package br.unb.cic.td20.search;

import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.base.GPUSortingSearch;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WitnessSearchTest {
    private static CyclicTargetPair pair() {
        return CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),Cycle.of(0,1,2,3,4,5)).canonicalRepresentative();
    }
    @Test void sortingRequiresPersistenceBeforeAnyGpuWork() {
        assertThrows(IllegalStateException.class, () -> WitnessSearch.search(pair(), 2));
        assertThrows(IllegalStateException.class, () -> WitnessSearch.searchGpu(pair(), 2));
    }

    @Test void reconstructionDispatchesUncachedClassesWithoutAnyCpuPrefix(@TempDir Path path) {
        String previous=System.getProperty("td20.cpuPrefixDepth");
        System.setProperty("td20.cpuPrefixDepth","-1");
        try (var writer = new AsyncCertificateWriter(path, 60)) {
            WitnessSearch.installWriter(writer);
            try {
                var dispatched=new java.util.concurrent.atomic.AtomicInteger();
                var result=WitnessSearch.searchGpu(pair(),2,(input,budget)->{
                    assertEquals(2,budget);assertEquals(pair().extendedToricClassKey(),input.extendedToricClassKey());
                    dispatched.incrementAndGet();
                    return new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                            java.util.List.of(new int[]{0,1,2}));
                });
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE,result.disposition());
                writer.flush();
                assertEquals(result,WitnessSearch.searchGpu(pair(),2,(input,budget)->{
                    fail("A stored certificate must avoid GPU dispatch");return null;
                }));
                assertEquals(1,dispatched.get());
                assertTrue(writer.findResult(pair(), 2).isPresent());
                assertTrue(writer.findResult(pair(), 1).isEmpty());
            } finally { WitnessSearch.uninstallWriter(writer); }
        } finally {
            if(previous==null)System.clearProperty("td20.cpuPrefixDepth");else System.setProperty("td20.cpuPrefixDepth",previous);
        }
    }

    @Test void gpuResidualsAndInvalidCertificatesCannotBecomeDischarges(@TempDir Path path) {
        try(var writer=new AsyncCertificateWriter(path,60)) {
            WitnessSearch.installWriter(writer);
            try {
                assertEquals(WitnessSearch.Disposition.RESIDUAL,WitnessSearch.searchGpu(pair(),2,(input,budget)->
                        new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.RESIDUAL,java.util.List.of())).disposition());
                assertThrows(IllegalArgumentException.class,()->WitnessSearch.searchGpu(pair(),2,(input,budget)->
                        new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.COMPLETE,java.util.List.of())));
                writer.flush();assertTrue(writer.findResult(pair(),2).isEmpty());
            } finally {WitnessSearch.uninstallWriter(writer);}
        }
    }

    @Test void interruptedCpuSearchStopsWithoutReturningACertificate() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> CpuPrefixSearch.search(pair(), 2, 10, 16777216));
        } finally { Thread.interrupted(); }
    }

    @Test void largeBatchReplaysEveryHitBeforeDispatch(@TempDir Path path) {
        try(var writer=new AsyncCertificateWriter(path,60)) {
            WitnessSearch.installWriter(writer);
            try {
                writer.enqueueClosure(pair(),java.util.List.of(Cycle.of(0,1,2)),2);writer.flush();
                var batch=java.util.Collections.nCopies(4096,pair());
                var hits=WitnessSearch.lookupBatch(batch,2);
                assertEquals(4096,hits.length);
                for(boolean hit:hits)assertTrue(hit);
                for(boolean hit:WitnessSearch.lookupBatch(batch,1))assertFalse(hit);
            } finally {WitnessSearch.uninstallWriter(writer);}
        }
    }

    @Test void timedCpuPrefixEitherSettlesOrFallsThroughWithTheFullBudget(@TempDir Path path) {
        try (var writer = new AsyncCertificateWriter(path.resolve("settled"), 60)) {
            WitnessSearch.installWriter(writer);
            try {
                var result = WitnessSearch.searchWithCpuPrefixTimeout(pair(), 2, 1000,
                        (input, budget) -> { fail("The CPU prefix should settle this pair"); return null; });
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertEquals(1, result.moves().size());
            } finally { WitnessSearch.uninstallWriter(writer); }
        }
        try (var writer = new AsyncCertificateWriter(path.resolve("timeout"), 60)) {
            WitnessSearch.installWriter(writer);
            try {
                var dispatched = new java.util.concurrent.atomic.AtomicInteger();
                var result = WitnessSearch.searchWithCpuPrefixTimeout(pair(), 2, 0, (input, budget) -> {
                    assertEquals(2, budget);
                    assertEquals(pair().extendedToricClassKey(), input.extendedToricClassKey());
                    dispatched.incrementAndGet();
                    return new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                            java.util.List.of(new int[]{0,1,2}));
                });
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
                assertEquals(1, dispatched.get());
            } finally { WitnessSearch.uninstallWriter(writer); }
        }
        assertThrows(IllegalArgumentException.class,
                () -> WitnessSearch.searchWithCpuPrefixTimeout(pair(), 2, -1, (input,budget) -> null));
    }
    @Test
    void cpuClosureIsPersistedReplayedAndNotACompleteWitness(@TempDir Path path) {
        try (var writer = new AsyncCertificateWriter(path,60)) {
            WitnessSearch.installWriter(writer);
            try {
                var result = WitnessSearch.search(pair(),2);
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE,result.disposition());
                assertEquals(1,result.moves().size());
                assertThrows(IllegalArgumentException.class,()->CertificateValidator.validate(pair(),result.moves(),2));
                writer.flush();
                assertTrue(writer.find(pair(),1).isEmpty());
                assertEquals(result.moves(),writer.find(pair(),2).orElseThrow());
                assertEquals(result,WitnessSearch.search(pair(),2));
            } finally {
                WitnessSearch.uninstallWriter(writer);
                WitnessSearch.shutdownGpuStats();
            }
        }
    }
}
