package br.unb.cic.td20.emission;

import br.unb.cic.td20.avoidance.DiskStateSet;
import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.joint.JointInventory;
import br.unb.cic.td20.joint.JointRootGenerator;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.search.WitnessSearch;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WitnessEmitterTest {
    @Test void allDirectRootsReceiveTheFullBudgetAndCountsMatch() {
        int[] partition = {3,2,2};
        long u = DirectTwistedBraceletGenerator.countRepresentatives(partition,new boolean[3]);
        var oriented = JointRootGenerator.count(partition);
        assertTrue(u > 0);
        var row = new JointInventory.Root(1,partition,u,oriented.jointResidualClasses(),0,oriented.jointResidualClasses());
        var complete = new LongAdder();
        try (var progress = new WitnessEmitter.Progress()) {
            var result = WitnessEmitter.emitRoot(row,(pair,budget) -> {
                assertEquals(11,budget);
                complete.increment();
                return false;
            },progress);
            assertEquals(u+oriented.jointResidualClasses(),complete.sum());
            assertEquals(u+oriented.jointResidualClasses(),result.unresolved());
            var wrong = new JointInventory.Root(1,partition,u+1,oriented.jointResidualClasses(),0,oriented.jointResidualClasses());
            assertThrows(IllegalStateException.class, () -> WitnessEmitter.emitRoot(wrong,(p,b) -> true,progress));
        }
    }
    @Test void missingEndpointWitnessesPopulateFinalWorkWithoutMarkers(@TempDir Path directory) {
        int[] partition = {3,2,2};
        long expected = DirectTwistedBraceletGenerator.countRepresentatives(partition,new boolean[3]);
        var endpoint = new JointInventory.Endpoint(1,partition,new boolean[3]);
        try (var states = new DiskStateSet(directory.resolve("states")); var progress = new WitnessEmitter.Progress()) {
            WitnessEmitter.emitEndpoint(endpoint,(pair,budget) -> {
                assertEquals(8,budget); return false;
            },states,progress);
            assertTrue(expected > 0); assertEquals(expected,states.seal());
        }
    }
    @Test void malformedScopeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WitnessEmitter.Options.parse(new String[]{"--partitions=0-83"},Path.of(".")));
        assertThrows(IllegalArgumentException.class, () -> WitnessEmitter.Options.parse(new String[]{"--phase=unknown"},Path.of(".")));
        assertThrows(IllegalArgumentException.class, () -> WitnessEmitter.Options.parse(new String[]{"--resume-work=old"},Path.of(".")));
        assertThrows(IllegalArgumentException.class, () -> WitnessEmitter.Options.parse(
                new String[]{"--phase=avoidance", "--resume-work=old", "--endpoints=8-12"},Path.of(".")));
        assertEquals(Path.of("old").toAbsolutePath().normalize(), WitnessEmitter.Options.parse(
                new String[]{"--phase=avoidance", "--resume-work=old"},Path.of(".")).resumeWork());
    }

    @Test void reconstructedRootUsesCpuPrefixAndPersistsItsClosure(@TempDir Path directory) {
        var pair = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),
                Cycle.of(0,1,2,3,4,5)).canonicalRepresentative();
        try (var writer = new AsyncCertificateWriter(directory.resolve("certificates"), 60)) {
            WitnessSearch.installWriter(writer);
            try {
                assertTrue(WitnessEmitter.settleReconstructed(pair, 2, writer));
                writer.flush();
                assertEquals(1, writer.findResult(pair, 2).orElseThrow().moves().size());
            } finally { WitnessSearch.uninstallWriter(writer); }
        }
    }
}
