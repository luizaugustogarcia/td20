package br.unb.cic.td20.experimental;

import br.unb.cic.td20.joint.CyclicTargetMoves;
import br.unb.cic.td20.model.PrimitivePair;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.HashMap;
import br.unb.cic.td20.avoidance.PrimitiveInverseReconstruction;
import br.unb.cic.td20.model.PrimitiveCanonicalizer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionAlternativesBenchmarkTest {
    @Test void twoSidedImmediatePredicateAgreesWithEverySupportThroughEightSymbols() {
        var predicate = new ReconstructionAlternativesBenchmark.ImmediateClosure();
        long[] checked = {0};
        for (int n = 3; n <= 8; n++) {
            int[] omega = IntStream.range(0, n).toArray(), beta = omega.clone();
            enumerate(omega, 0, candidate -> {
                for (int x = 0; x < candidate.length; x++) if (candidate[x] == x) return;
                if (!CyclicTargetMoves.isFullTargetCycle(candidate, beta)) return;
                int move = predicate.find(candidate, beta, candidate.length);
                boolean expected = oracle(candidate, beta);
                assertEquals(expected, move >= 0, Arrays.toString(candidate));
                if (move >= 0) ReconstructionAlternativesBenchmark.replay(candidate, beta, candidate.length, move);
                checked[0]++;
            });
        }
        assertTrue(checked[0] > 1000);
    }

    @Test void fixedPointsMustBeContracted() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReconstructionAlternativesBenchmark.ImmediateClosure().find(
                        new int[]{0, 1, 2}, new int[]{0, 1, 2}, 3));
    }

    @Test void familyPrunedInversePreservesEveryUnclosedClassAndItsMultiplicityThroughSevenSymbols() {
        var baseline = new PrimitiveInverseReconstruction();
        var pruned = new FamilyPrunedInverseReconstruction();
        var predicate = new ReconstructionAlternativesBenchmark.ImmediateClosure();
        var keys = new PrimitiveCanonicalizer();
        for (int n = 3; n <= 7; n++) {
            int[] omega = IntStream.range(0, n).toArray(), beta = omega.clone();
            enumerate(omega, 0, candidate -> {
                for (int x = 0; x < candidate.length; x++) if (candidate[x] == x) return;
                if (!CyclicTargetMoves.isFullTargetCycle(candidate, beta)) return;
                PrimitivePair pair = PrimitivePair.of(candidate, beta);
                for (int gain = 0; gain <= 1; gain++) {
                    var expected = new HashMap<ByteBuffer, Long>();
                    var actual = new HashMap<ByteBuffer, Long>();
                    long[] occurrences = new long[2];
                    baseline.preimages(pair, gain, (w, b, length) -> {
                        if (predicate.find(w, b, length) >= 0) return;
                        expected.merge(ByteBuffer.wrap(keys.keyForKnownPair(w, b, length)), 1L, Long::sum); occurrences[0]++;
                    });
                    pruned.preimages(pair, gain, (w, b, length) -> {
                        if (predicate.find(w, b, length) >= 0) return;
                        actual.merge(ByteBuffer.wrap(keys.keyForKnownPair(w, b, length)), 1L, Long::sum); occurrences[1]++;
                    });
                    assertEquals(expected, actual, Arrays.toString(candidate) + " gain=" + gain);
                    assertEquals(occurrences[0], occurrences[1]);
                }
            });
        }
    }

    private static boolean oracle(int[] omega, int[] beta) {
        if (oneSide(omega, beta)) return true;
        PrimitivePair pair = PrimitivePair.of(omega, beta);
        int[] inverse = new int[omega.length], rho = new int[omega.length];
        for (int i = 0; i < omega.length; i++) { inverse[omega[i]] = i; rho[i] = pair.rhoAt(i); }
        return oneSide(inverse, rho);
    }

    private static boolean oneSide(int[] omega, int[] beta) {
        for (int i = 0; i < beta.length - 2; i++)
            for (int j = i + 1; j < beta.length - 1; j++)
                for (int k = j + 1; k < beta.length; k++) {
                    var move = new CyclicTargetMoves.Move(beta[i], beta[j], beta[k]);
                    int[] moved = CyclicTargetMoves.applyToOmega(omega, move);
                    int fixed = 0;
                    for (int x = 0; x < moved.length; x++) if (moved[x] == x) fixed++;
                    if (fixed >= 2) return true;
                }
        return false;
    }

    private static void enumerate(int[] values, int index, java.util.function.Consumer<int[]> action) {
        if (index == values.length) { action.accept(values); return; }
        for (int j = index; j < values.length; j++) {
            int swap = values[index]; values[index] = values[j]; values[j] = swap;
            enumerate(values, index + 1, action);
            swap = values[index]; values[index] = values[j]; values[j] = swap;
        }
    }
}
