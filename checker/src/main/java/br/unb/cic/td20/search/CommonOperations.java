package br.unb.cic.td20.search;

import br.unb.cic.td20.permutation.Cycle;
import lombok.val;

import java.util.Arrays;
import java.util.Collection;

/**
 * Cyclic-order operations shared by canonicalization and witness replay.
 */
public final class CommonOperations {

    private CommonOperations() {
    }

    public static Cycle applyTranspositionOptimized(final Cycle beta, final Cycle move) {
        val indexes = new int[] { beta.indexOf(move.get(0)), beta.indexOf(move.get(1)), beta.indexOf(move.get(2)) };
        java.util.Arrays.sort(indexes);

        val result = new int[beta.size()];
        System.arraycopy(beta.getSymbols(), 0, result, 0, indexes[0]);
        System.arraycopy(beta.getSymbols(),
                indexes[1],
                result,
                indexes[0],
                indexes[2] - indexes[1]);
        System.arraycopy(beta.getSymbols(),
                indexes[0],
                result,
                indexes[0] + indexes[2] - indexes[1],
                indexes[1] - indexes[0]);
        System.arraycopy(beta.getSymbols(),
                indexes[2],
                result,
                indexes[2],
                beta.size() - indexes[2]);

        return Cycle.of(result);
    }

    public static Cycle[] cycleIndex(final Collection<Cycle> cycles, final Cycle beta) {
        val result = new Cycle[beta.size()];
        for (val cycle : cycles) {
            for (val symbol : cycle.getSymbols()) {
                result[symbol] = cycle;
            }
        }

        return result;
    }

    public static boolean areSymbolsInCyclicOrder(final Cycle cycle, final int... symbols) {
        if (symbols.length <= 1) {
            return true;
        }

        val indexes = cycle.getSymbolIndexes();
        var wraps = 0;
        for (var index = 0; index < symbols.length; index++) {
            val current = indexes[symbols[index]];
            val next = indexes[symbols[(index + 1) % symbols.length]];
            if (current > next && ++wraps > 1) {
                return false;
            }
        }

        return true;
    }

    public static boolean isOriented(final Cycle beta, final Cycle cycle) {
        return !areSymbolsInCyclicOrder(beta.getInverse(), cycle.getSymbols());
    }

    static byte[] applyTransposition(final byte[] beta, final int i, final int j, final int k) {
        final byte[] result = Arrays.copyOf(beta, beta.length);
        System.arraycopy(beta, j, result, i, k - j);
        System.arraycopy(beta, i, result, i + k - j, j - i);

        return result;
    }
}
