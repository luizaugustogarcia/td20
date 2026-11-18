package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.compat.LegacyPairs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PrimitivePersistenceTest {
    @Test void primitiveInverseBatchesPreserveLegacyKeysDeduplicationAndResume(@TempDir Path directory) {
        var child = ActiveErikssonAvoidanceTest.pair(5);
        var expected = new TreeSet<String>();
        for (int gain = 0; gain <= 3; gain++) {
            int budget = 8 + gain;
            ErikssonInverseReconstruction.preimages(child,gain,parent -> {
                byte[] raw = CertificateCodec.keyFor(parent.canonicalRepresentative());
                byte[] key = new byte[3 + raw.length];
                key[0] = 3; key[1] = (byte) budget; key[2] = (byte) parent.getBeta().size();
                System.arraycopy(raw,0,key,3,raw.length);
                expected.add(HexFormat.of().formatHex(key));
            });
        }
        String oldBatch = System.getProperty("td20.reconstructionGeneratedBatchKeys");
        System.setProperty("td20.reconstructionGeneratedBatchKeys","17");
        Path path = directory.resolve("states");
        try {
            try (var states = new DiskStateSet(path)) {
                for (int repetition = 0; repetition < 2; repetition++)
                    for (int gain = 0; gain <= 3; gain++)
                        states.addInverseGenerated(LegacyPairs.fromLegacy(child),8 + gain,gain);
                assertEquals(expected.size(),states.seal());
                assertEquals(List.copyOf(expected),keys(states));
                assertThrows(IllegalStateException.class,()->states.addInverseGenerated(LegacyPairs.fromLegacy(child),9,2));
            }
            try (var states = DiskStateSet.openSealedForScan(path,expected.size())) {
                var scanned = ConcurrentHashMap.<String>newKeySet();
                states.forEachConcurrently(2,state -> {
                    assertTrue(state.primitivePair().isCanonical());
                    assertTrue(scanned.add(HexFormat.of().formatHex(DiskStateSet.encode(state))));
                });
                assertEquals(expected,scanned); assertEquals(expected.size(),states.knownSize());
            }
        } finally {
            if (oldBatch == null) System.clearProperty("td20.reconstructionGeneratedBatchKeys");
            else System.setProperty("td20.reconstructionGeneratedBatchKeys",oldBatch);
        }
    }

    @Test void primitivePipelineKeepsBatchLookupCpuThenGpuAndEveryResidual(@TempDir Path directory) {
        var cpuSeen = ConcurrentHashMap.<Integer>newKeySet();
        var gpuSeen = ConcurrentHashMap.<Integer>newKeySet();
        var residuals = ConcurrentHashMap.<Integer>newKeySet();
        try (var states = new DiskStateSet(directory.resolve("states"))) {
            var pair = LegacyPairs.fromLegacy(ActiveErikssonAvoidanceTest.pair(7));
            for (int budget = 1; budget <= 8; budget++) states.add(pair,budget);
            ReconstructionPipeline.processPrimitive(states,directory.resolve("queue"),2,2, batch -> {
                var hits = new boolean[batch.size()];
                for (int i = 0; i < hits.length; i++) hits[i] = batch.get(i).maximumMoves() == 2;
                return hits;
            },(input,budget) -> {
                assertTrue(input.isFixedPointFree()); assertTrue(cpuSeen.add(budget)); return budget < 5;
            },(input,budget) -> {
                assertTrue(cpuSeen.contains(budget)); assertTrue(gpuSeen.add(budget)); return budget % 2 == 0;
            },state -> assertTrue(residuals.add(state.maximumMoves())));
            assertEquals(java.util.Set.of(1,3,4,5,6,7,8),cpuSeen);
            assertEquals(java.util.Set.of(5,6,7,8),gpuSeen);
            assertEquals(java.util.Set.of(5,7),residuals); assertEquals(8,states.knownSize());
        }
    }

    private static List<String> keys(DiskStateSet states) {
        var keys = new ArrayList<String>();
        states.forEach(state -> keys.add(HexFormat.of().formatHex(DiskStateSet.encode(state))));
        return keys;
    }
}
