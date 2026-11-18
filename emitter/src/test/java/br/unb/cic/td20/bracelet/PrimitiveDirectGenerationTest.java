package br.unb.cic.td20.bracelet;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.model.PrimitivePair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PrimitiveDirectGenerationTest {
    @Test
    void primitiveLeavesPreserveLegacyRepresentativesKeysAndTargets() {
        int[][] partitions={{3,3},{5,3},{5,3,3},{8,7,2}};
        boolean[][] orientations={{true,false},{true,false},{true,false,false},{false,false,false}};
        for (int row=0;row<partitions.length;row++) {
            var expected=new ArrayList<String>();
            DirectTwistedBraceletGenerator.generateRepresentatives(partitions[row],orientations[row],
                    pair -> expected.add(HexFormat.of().formatHex(CertificateCodec.keyFor(pair))));
            var actual=new ArrayList<String>();
            DirectTwistedBraceletGenerator.generatePrimitiveRepresentatives(partitions[row],orientations[row],pair -> {
                assertTrue(pair.isCanonical());
                assertSame(pair,pair.canonicalRepresentative());
                assertTrue(pair.isFixedPointFree());
                assertArrayEquals(CertificateCodec.keyFor(LegacyPairs.toLegacy(pair)),pair.canonicalKey());
                for (int i=0;i<pair.size();i++) assertEquals(
                        pair.omegaImage((pair.rhoAt(i)+1)%pair.size()),pair.rhoAt((i+1)%pair.size()));
                actual.add(key(pair));
            });
            assertEquals(expected,actual);
        }
    }

    @Test
    void primitiveConcurrentAndWorkReplayCoverExactlyTheSequentialClasses() {
        int[] partition={5,3,3}; boolean[] orientation={true,false,false};
        Set<String> expected=ConcurrentHashMap.newKeySet();
        DirectTwistedBraceletGenerator.generatePrimitiveRepresentatives(partition,orientation,pair ->
                assertTrue(expected.add(key(pair))));
        Set<String> concurrent=ConcurrentHashMap.newKeySet();
        DirectTwistedBraceletGenerator.generatePrimitiveRepresentativesConcurrently(
                partition,orientation,() -> {},pair -> assertTrue(concurrent.add(key(pair))));
        assertEquals(expected,concurrent);
        Set<String> replay=ConcurrentHashMap.newKeySet();
        for (var work:DirectTwistedBraceletGenerator.planWork(partition,orientation,23))
            DirectTwistedBraceletGenerator.generatePrimitiveWork(partition,orientation,work,() -> {},
                    pair -> assertTrue(replay.add(key(pair))));
        assertEquals(expected,replay);
    }

    @Test
    void terminalOnlyWorkIsCountedAndReplayedExactly() {
        int[] partition={5}; boolean[] orientation={true};
        var work=DirectTwistedBraceletGenerator.planWork(partition,orientation,100);
        assertTrue(work.stream().anyMatch(DirectTwistedBraceletGenerator.GenerationWork::leavesOnly));
        var count=new AtomicInteger();
        for (var item:work) DirectTwistedBraceletGenerator.generatePrimitiveWork(
                partition,orientation,item,() -> {},ignored -> count.incrementAndGet());
        assertEquals(DirectTwistedBraceletGenerator.countRepresentatives(partition,orientation),count.get());
    }

    private static String key(PrimitivePair pair) { return HexFormat.of().formatHex(pair.canonicalKey()); }
}
