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
import java.nio.file.Files;
import java.util.List;
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
            var work = new ErikssonWitnessEmitter.EndpointWork(endpoint,expected,endpoint.elementaryClosure()?0:expected);
            ErikssonWitnessEmitter.emitEndpoint(work,(pair,budget) -> {
                assertEquals(ErikssonRoute.ENDPOINT_BUDGET,budget); return false;
            },states,progress);
            assertTrue(expected > 0); assertEquals(endpoint.elementaryClosure()?0:expected,states.seal());
        }
    }
    @Test void publishedZeroEndpointSkipsGenerationAndDischarge() {
        // This small type has classes; a deliberately false published zero
        // affects emission scheduling only, leaving checking independent.
        var endpoint = new ErikssonInventory.Endpoint(1,new int[]{3,2,2},new boolean[3]);
        assertTrue(DirectTwistedBraceletGenerator.countRepresentatives(endpoint.partition(),endpoint.orientedByPart())>0);
        var work = new ErikssonWitnessEmitter.EndpointWork(endpoint,0,0);
        try (var progress = new ErikssonWitnessEmitter.Progress()) {
            ErikssonWitnessEmitter.emitEndpoint(work,(pair,budget)->{
                fail("Empty endpoint must not dispatch sorting"); return false;
            },null,progress);
        }
    }

    @Test void publishedEndpointInventoryIsCompleteAndStrict(@TempDir Path directory) throws Exception {
        Path paper=br.unb.cic.td20.runtime.ProjectPaths.root(Path.of(""))
                .resolve("docs/td20-reduction-count.tex");
        String source=Files.readString(paper);
        var work=ErikssonWitnessEmitter.readEndpointWork(paper);
        assertEquals(143,work.size());
        assertEquals(14_098_353_328L,work.stream().mapToLong(ErikssonWitnessEmitter.EndpointWork::classes).sum());
        assertEquals(11_523_939_269L,work.stream().mapToLong(ErikssonWitnessEmitter.EndpointWork::requiringDischarge).sum());
        String first=source.lines().filter(s->s.startsWith("\\censusendpointrow{1}{")).findFirst().orElseThrow();
        for (String replacement : List.of("",first+"\n"+first,
                first.replace("14_{\\mathrm U}","14_{\\mathrm O}"),
                first.replace("{16}{5}{3}{8}","{16}{5}{3}{7}"),
                first.replace("{0}{0}","{pending}{0}"),
                first.replace("{0}{0}","{0}{1}"))) {
            Path changed=directory.resolve("changed.tex");
            Files.writeString(changed,source.replace(first,replacement));
            assertThrows(IllegalArgumentException.class,()->ErikssonWitnessEmitter.readEndpointWork(changed));
        }
        Path changed=directory.resolve("changed.tex");
        Files.writeString(changed,source.replace("% END ERIKSSON_ENDPOINT_TABLE_V1","% missing end"));
        assertThrows(IllegalArgumentException.class,()->ErikssonWitnessEmitter.readEndpointWork(changed));
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
        var pair = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),
                Cycle.of(0,1,2,3,4,5)).canonicalRepresentative();
        try (var writer = new AsyncCertificateWriter(directory.resolve("certificates"), 60)) {
            WitnessSearch.installWriter(writer);
            try {
                assertTrue(ErikssonWitnessEmitter.settleReconstructedCpu(pair, 2));
                writer.flush();
                assertEquals(1, writer.findResult(pair, 2).orElseThrow().moves().size());
            } finally { WitnessSearch.uninstallWriter(writer); }
        }
    }
}
