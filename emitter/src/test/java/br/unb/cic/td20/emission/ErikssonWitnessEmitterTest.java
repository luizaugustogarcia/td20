package br.unb.cic.td20.emission;

import br.unb.cic.td20.avoidance.DiskStateSet;
import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.joint.ErikssonInventory;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.search.WitnessSearch;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ErikssonWitnessEmitterTest {


    @Test void missingEndpointWitnessesPopulateFinalWorkWithoutMarkers(@TempDir Path directory) {
        int[] partition = {3,2,2};
        long expected = DirectTwistedBraceletGenerator.countRepresentatives(partition,new boolean[3]);
        var endpoint = new ErikssonInventory.Endpoint(1,partition,new boolean[3]);
        try (var states = new DiskStateSet(directory.resolve("states")); var progress = new ErikssonWitnessEmitter.Progress()) {
            ErikssonWitnessEmitter.emitEndpoint(endpoint,(pair,budget) -> {
                assertEquals(ErikssonRoute.ENDPOINT_BUDGET,budget); return false;
            },states,progress);
            assertTrue(expected > 0); assertEquals(endpoint.elementaryClosure()?0:expected,states.seal());
        }
    }
    @Test void malformedScopeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ErikssonWitnessEmitter.Options.parse(new String[]{"--partitions=0-1"},Path.of(".")));
        assertThrows(IllegalArgumentException.class, () -> ErikssonWitnessEmitter.Options.parse(new String[]{"--phase=unknown"},Path.of(".")));
        assertThrows(IllegalArgumentException.class, () -> ErikssonWitnessEmitter.Options.parse(new String[]{"--resume-work=old"},Path.of(".")));
        assertThrows(IllegalArgumentException.class, () -> ErikssonWitnessEmitter.Options.parse(
                new String[]{"--phase=avoidance", "--resume-work=old", "--endpoints=8-12"},Path.of(".")));
        assertEquals(Path.of("old").toAbsolutePath().normalize(), ErikssonWitnessEmitter.Options.parse(
                new String[]{"--phase=avoidance", "--resume-work=old"},Path.of(".")).resumeWork());
    }

    @Test void reconstructedRootUsesCpuPrefixAndPersistsItsClosure(@TempDir Path directory) {
        final String previousTimeout = System.getProperty("td20.cpuPrefixMillis");
        System.setProperty("td20.cpuPrefixMillis", "60000");
        var pair = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),
                Cycle.of(0,1,2,3,4,5)).canonicalRepresentative();
        try (var writer = new AsyncCertificateWriter(directory.resolve("certificates"), 60)) {
            WitnessSearch.installWriter(writer);
            try {
                assertTrue(ErikssonWitnessEmitter.settleReconstructedCpu(pair, 2));
                writer.flush();
                assertEquals(1, writer.findResult(pair, 2).orElseThrow().moves().size());
            } finally { WitnessSearch.uninstallWriter(writer); }
        } finally {
            if (previousTimeout == null) System.clearProperty("td20.cpuPrefixMillis");
            else System.setProperty("td20.cpuPrefixMillis", previousTimeout);
        }
    }
}
