package br.unb.cic.td20.bracelet;

import lombok.val;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TwistedBraceletBurnsideCounterTest {

    @Test
    void reproducesIndependentlyEnumeratedFixedSets() {
        try (val executor = Executors.newFixedThreadPool(4)) {
            val partition = new int[] { 5, 3, 3 };
            val oriented = new boolean[] { true, false, false };
            val key = TwistedBraceletBurnsideCounter.annotatedKey(partition, oriented);
            assertEquals(BigInteger.valueOf(9702),
                    TwistedBraceletBurnsideCounter.identityFixed(partition, oriented, executor));
            assertEquals(0, TwistedBraceletBurnsideCounter.rotationFixed(11, 1, executor).getOrDefault(key, 0L));
            assertEquals(76, TwistedBraceletBurnsideCounter.reflectionFixed(11, 0, executor).getOrDefault(key, 0L));
            assertEquals(479, TwistedBraceletBurnsideCounter.count(partition, oriented, executor));
        }
    }

    @Test
    void matchesDirectGenerationForEveryAnnotatedPartitionThroughNineSymbols() {
        try (val executor = Executors.newFixedThreadPool(4)) {
            for (var n = 2; n <= 9; n++) {
                comparePartitions(n, n, new ArrayList<>(), executor);
            }
        }
    }

    private static void comparePartitions(final int remaining,
            final int maximum,
            final ArrayList<Integer> parts,
            final ExecutorService executor) {
        if (remaining == 0) {
            val partition = parts.stream().mapToInt(Integer::intValue).toArray();
            for (var mask = 0; mask < 1 << partition.length; mask++) {
                val oriented = new boolean[partition.length];
                var admissible = true;
                for (var i = 0; i < partition.length; i++) {
                    oriented[i] = (mask & (1 << i)) != 0;
                    admissible &= !(partition[i] == 2 && oriented[i]);
                    admissible &= !(i > 0 && partition[i] == partition[i - 1] && oriented[i - 1] && !oriented[i]);
                }
                if (admissible) {
                    assertEquals(DirectTwistedBraceletGenerator.countRepresentatives(partition, oriented),
                            TwistedBraceletBurnsideCounter.count(partition, oriented, executor),
                            java.util.Arrays.toString(partition) + " " + java.util.Arrays.toString(oriented));
                }
            }

            return;
        }
        for (var k = Math.min(remaining, maximum); k >= 2; k--) {
            parts.add(k);
            comparePartitions(remaining - k, k, parts, executor);
            parts.removeLast();
        }
    }

    @Test
    void burnsideEntryPointCountsMixedAndUnorientedTypes() {
        assertEquals(479,
                DirectTwistedBraceletGenerator.countRepresentativesByBurnside(new int[] { 5, 3, 3 }, new boolean[] { true, false, false }));
        assertEquals(1,
                DirectTwistedBraceletGenerator.countRepresentativesByBurnside(new int[] { 3, 3 }, new boolean[] { false, false }));
        assertEquals(0,
                DirectTwistedBraceletGenerator.countRepresentativesByBurnside(new int[] { 3 }, new boolean[] { false }));
        assertEquals(1,
                DirectTwistedBraceletGenerator.countRepresentativesByBurnside(new int[] { 3 }, new boolean[] { true }));
        assertThrows(IllegalArgumentException.class,
                () -> DirectTwistedBraceletGenerator.countRepresentativesByBurnside(new int[] { 31 }, new boolean[] { true }));
    }
}
