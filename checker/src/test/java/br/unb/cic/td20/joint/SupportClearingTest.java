package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SupportClearingTest {

    @Test
    void allAdmittedPowersAndTheirRelabelingsMatchTheRootBudget() {
        power(9, new int[] { 18, 16, 5, 19, 20, 15, 2, 3, 4, 1, 7, 8, 9, 14, 6, 13, 0, 10, 12, 11, 17 });
        power(11, new int[] { 12, 6, 14, 7, 15, 17, 9, 16, 19, 18, 0, 20, 3, 2, 4, 5, 10, 8, 1, 11, 13 });
        power(13, new int[] { 14, 11, 12, 6, 13, 16, 7, 15, 17, 20, 1, 2, 4, 5, 18, 3, 8, 9, 19, 0, 10 });
        power(13, new int[] { 15, 16, 19, 20, 0, 10, 1, 2, 17, 4, 8, 6, 7, 18, 9, 11, 12, 5, 3, 14, 13 });
        power(13, new int[] { 17, 16, 13, 19, 0, 3, 4, 5, 1, 6, 8, 7, 9, 20, 11, 12, 10, 14, 2, 15, 18 });
        power(17, new int[] { 12, 13, 14, 15, 16, 7, 17, 5, 20, 18, 19, 0, 1, 2, 3, 4, 6, 9, 10, 11, 8 });
        power(17, new int[] { 17, 18, 19, 20, 0, 1, 2, 12, 3, 13, 4, 5, 7, 9, 6, 8, 10, 11, 14, 15, 16 });
        power(17, new int[] { 19, 20, 0, 1, 2, 3, 4, 5, 6, 7, 14, 8, 9, 11, 10, 17, 12, 15, 13, 16, 18 });
        power(0, new int[] { 16, 15, 17, 8, 0, 11, 18, 4, 14, 19, 5, 3, 7, 1, 10, 2, 20, 6, 9, 13, 12 });
        power(0, new int[] { 3, 13, 16, 5, 14, 0, 15, 17, 18, 19, 20, 2, 1, 4, 6, 7, 11, 8, 9, 10, 12 });
    }

    private static void power(final int expected, final int[] images) {
        val pair = pair(images);
        assertEquals(expected, SupportClearing.closingLength(pair));

        val relabeled = new MulticyclePermutation();
        for (val cycle : pair.getOmega()) {
            relabeled.add(Cycle.of(Arrays.stream(cycle.getSymbols()).map(x -> (5 * x + 3) % 21).toArray()));
        }

        val beta = Cycle.of(Arrays.stream(pair.getBeta().getSymbols()).map(x -> (5 * x + 3) % 21).toArray());
        assertEquals(expected, SupportClearing.closingLength(CyclicTargetPair.of(relabeled, beta)));
    }

    private static CyclicTargetPair pair(final int[] images) {
        val omega = new MulticyclePermutation();
        val seen = new boolean[images.length];
        for (int x = 0; x < images.length; x++) {
            if (!seen[x]) {
                val symbols = new int[images.length];
                int size = 0, y = x;
                do {
                    symbols[size++] = y;
                    seen[y] = true;
                    y = images[y];
                } while (y != x);

                omega.add(Cycle.of(Arrays.copyOf(symbols, size)));
            }
        }

        val beta = new int[images.length];
        Arrays.setAll(beta, i -> i);

        return CyclicTargetPair.of(omega, Cycle.of(beta));
    }
}
