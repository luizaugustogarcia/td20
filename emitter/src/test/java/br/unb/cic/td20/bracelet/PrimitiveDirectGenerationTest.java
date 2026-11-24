package br.unb.cic.td20.bracelet;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.model.PrimitivePair;
import lombok.val;
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
        final int[][] partitions = { { 3, 3 }, { 5, 3 }, { 5, 3, 3 }, { 8, 7, 2 } };
        final boolean[][] orientations = { { true, false }, { true, false }, { true, false, false }, { false, false, false } };
        for (int row = 0; row < partitions.length; row++) {
            val expected = new ArrayList<String>();
            DirectTwistedBraceletGenerator.generateRepresentatives(partitions[row],
                    orientations[row],
                    pair -> expected.add(HexFormat.of().formatHex(CertificateCodec.keyFor(pair))));

            val actual = new ArrayList<String>();
            DirectTwistedBraceletGenerator.generatePrimitiveRepresentatives(partitions[row],
                    orientations[row],
                    pair -> {
                        assertTrue(pair.isCanonical());
                        assertSame(pair, pair.canonicalRepresentative());
                        assertTrue(pair.isFixedPointFree());
                        assertArrayEquals(CertificateCodec.keyFor(LegacyPairs.toLegacy(pair)), pair.canonicalKey());
                        for (int i = 0; i < pair.size(); i++) {
                            assertEquals(pair.omegaImage((pair.rhoAt(i) + 1) % pair.size()), pair.rhoAt((i + 1) % pair.size()));
                        }

                        actual.add(key(pair));
                    });
            assertEquals(expected, actual);
        }
    }

    @Test
    void primitiveConcurrentAndWorkReplayCoverExactlyTheSequentialClasses() {
        final int[] partition = { 5, 3, 3 };
        final boolean[] orientation = { true, false, false };
        final Set<String> expected = ConcurrentHashMap.newKeySet();
        DirectTwistedBraceletGenerator.generatePrimitiveRepresentatives(partition, orientation, pair -> assertTrue(expected.add(key(pair))));

        final Set<String> concurrent = ConcurrentHashMap.newKeySet();
        DirectTwistedBraceletGenerator.generatePrimitiveRepresentativesConcurrently(partition, orientation, () -> {
        }, pair -> assertTrue(concurrent.add(key(pair))));
        assertEquals(expected, concurrent);

        final Set<String> replay = ConcurrentHashMap.newKeySet();
        for (val work : DirectTwistedBraceletGenerator.planWork(partition, orientation, 23)) {
            DirectTwistedBraceletGenerator.generatePrimitiveWork(partition,
                    orientation,
                    work,
                    () -> {
                    },
                    pair -> assertTrue(replay.add(key(pair))));
        }

        assertEquals(expected, replay);
    }

    @Test
    void terminalOnlyWorkIsCountedAndReplayedExactly() {
        final int[] partition = { 5 };
        final boolean[] orientation = { true };
        val work = DirectTwistedBraceletGenerator.planWork(partition, orientation, 100);
        assertTrue(work.stream().anyMatch(DirectTwistedBraceletGenerator.GenerationWork::leavesOnly));

        val count = new AtomicInteger();
        for (val item : work) {
            DirectTwistedBraceletGenerator.generatePrimitiveWork(partition, orientation, item, () -> {
            }, ignored -> count.incrementAndGet());
        }

        assertEquals(DirectTwistedBraceletGenerator.countRepresentatives(partition, orientation), count.get());
    }

    private static String key(final PrimitivePair pair) {
        return HexFormat.of().formatHex(pair.canonicalKey());
    }
}
