package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SupportClearingTest {
    @Test
    void rootClearingPowersAreRecognizedIndependentlyOfSymbolLabels() {
        closing(9,new int[]{18,16,5,19,20,15,2,3,4,1,7,8,9,14,6,13,0,10,12,11,17});
        closing(11,new int[]{12,6,14,7,15,17,9,16,19,18,0,20,3,2,4,5,10,8,1,11,13});
        closing(13,new int[]{14,11,12,6,13,16,7,15,17,20,1,2,4,5,18,3,8,9,19,0,10});
        closing(13,new int[]{15,16,19,20,0,10,1,2,17,4,8,6,7,18,9,11,12,5,3,14,13});
        closing(13,new int[]{17,16,13,19,0,3,4,5,1,6,8,7,9,20,11,12,10,14,2,15,18});
        closing(17,new int[]{12,13,14,15,16,7,17,5,20,18,19,0,1,2,3,4,6,9,10,11,8});
        closing(17,new int[]{17,18,19,20,0,1,2,12,3,13,4,5,7,9,6,8,10,11,14,15,16});
        closing(17,new int[]{19,20,0,1,2,3,4,5,6,7,14,8,9,11,10,17,12,15,13,16,18});
        closing(0,new int[]{16,15,17,8,0,11,18,4,14,19,5,3,7,1,10,2,20,6,9,13,12});
        closing(0,new int[]{3,13,16,5,14,0,15,17,18,19,20,2,1,4,6,7,11,8,9,10,12});
    }

    @Test
    void onlyTheBudgetAdmittedLocalPowersAreAccepted() {
        final int[][] candidates = {
                {5, 3, 0}, {7, 4, 0}, {9, 5, 0}, {9, 7, 1},
                {11, 6, 1}, {13, 7, 1}, {13, 10, 1}, {13, 11, 1},
                {15, 8, 0}, {17, 9, 1}, {17, 13, 1}, {17, 15, 1}
        };
        for (final int[] candidate : candidates) {
            final int length = candidate[0];
            final int exponent = candidate[1];
            final int[] omega = IntStream.range(0, 21).toArray();
            final int[] support = IntStream.range(0, length).toArray();
            for (int x = 0; x < length; x++) omega[x] = (x + exponent) % length;
            assertEquals(candidate[2] == 1, SupportClearing.admittedPower(omega, support, length),
                    "length=" + length + ", exponent=" + exponent);
            // An admitted local power has no applicable qualifying support.
            for (int a = 0; a < length - 2; a++) {
                for (int b = a + 1; b < length - 1; b++) {
                    for (int c = b + 1; c < length; c++) {
                        final var move = new JointReductionAnalysis.Move(a, b, c);
                        if (JointReductionAnalysis.gain(omega, move) == 0) continue;
                        final int[] next = JointReductionAnalysis.applyToOmega(omega, move);
                        assertNotEquals(JointReductionAnalysis.oddCycleCount(omega) + 2,
                                JointReductionAnalysis.oddCycleCount(next));
                    }
                }
            }
        }
    }

    private static void closing(final int expected, final int[] images) {
        final CyclicTargetPair pair = pair(images);
        assertEquals(expected, SupportClearing.closingLength(pair));
        final MulticyclePermutation relabeledOmega = new MulticyclePermutation();
        for (final Cycle cycle : pair.getOmega()) {
            relabeledOmega.add(Cycle.of(IntStream.of(cycle.getSymbols())
                    .map(x -> (5 * x + 3) % 21).toArray()));
        }
        final Cycle relabeledBeta = Cycle.of(IntStream.of(pair.getBeta().getSymbols())
                .map(x -> (5 * x + 3) % 21).toArray());
        assertEquals(expected, SupportClearing.closingLength(
                CyclicTargetPair.of(relabeledOmega, relabeledBeta)));
    }

    private static CyclicTargetPair pair(final int[] images) {
        final MulticyclePermutation omega = new MulticyclePermutation();
        final boolean[] seen = new boolean[images.length];
        for (int first = 0; first < images.length; first++) {
            if (seen[first]) continue;
            final int[] cycle = new int[images.length];
            int size = 0;
            int symbol = first;
            do {
                cycle[size++] = symbol;
                seen[symbol] = true;
                symbol = images[symbol];
            } while (symbol != first);
            omega.add(Cycle.of(Arrays.copyOf(cycle, size)));
        }
        return CyclicTargetPair.of(omega, Cycle.of(IntStream.range(0, images.length).toArray()));
    }
}
