package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.ExtendedToricClassKey;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class JointInverseReconstructionTest {
    private record Child(int gain, ExtendedToricClassKey key) { }

    @Test
    void everyInverseClassAgreesWithAllForwardSupportsOnFiveThroughEightSymbols() {
        long checkedChildren = 0;
        final long[] evenCycleFirstMoves = {0};
        for (int n = 5; n <= 8; n++) {
            final Map<Child, Set<ExtendedToricClassKey>> expected = new HashMap<>();
            final Map<Child, CyclicTargetPair> children = new HashMap<>();
            permutations(IntStream.range(0, n).toArray(), 0, omega -> {
                for (int x = 0; x < omega.length; x++) if (omega[x] == x) return;
                if (!fullTarget(omega)) return;
                final int oddBefore = oddCycles(omega);
                final ExtendedToricClassKey parentKey = pair(omega,
                        IntStream.range(0, omega.length).toArray()).extendedToricClassKey();
                for (int a = 0; a < omega.length - 2; a++) {
                    for (int b = a + 1; b < omega.length - 1; b++) {
                        for (int c = b + 1; c < omega.length; c++) {
                            // Direct permutation multiplication, without the inverse implementation's helpers.
                            final int[] successor = omega.clone();
                            successor[a] = omega[c];
                            successor[b] = omega[a];
                            successor[c] = omega[b];
                            int gain = 0;
                            for (int x = 0; x < successor.length; x++) if (successor[x] == x) gain++;
                            if (gain < 1 || gain > 2 || oddCycles(successor) != oddBefore + 2) continue;
                            if (gain == 2 && totallyUnoriented(omega)) continue;
                            if (gain == 1 && sameEvenCycle(omega, a, b, c)) evenCycleFirstMoves[0]++;
                            // Exchange the adjacent half-open intervals [a,b) and [b,c).
                            final int[] beta = new int[omega.length];
                            int index = 0;
                            for (int x = 0; x < a; x++) beta[index++] = x;
                            for (int x = b; x < c; x++) beta[index++] = x;
                            for (int x = a; x < b; x++) beta[index++] = x;
                            for (int x = c; x < omega.length; x++) beta[index++] = x;
                            final CyclicTargetPair child = contract(successor, beta);
                            final Child key = new Child(gain, child.extendedToricClassKey());
                            expected.computeIfAbsent(key, ignored -> new HashSet<>()).add(parentKey);
                            children.putIfAbsent(key, child.canonicalRepresentative());
                        }
                    }
                }
            });
            for (final var entry : expected.entrySet()) {
                final Child key = entry.getKey();
                final Set<ExtendedToricClassKey> actual = new HashSet<>();
                JointInverseReconstruction.preimages(children.get(key), key.gain,
                        parent -> actual.add(parent.extendedToricClassKey()));
                assertEquals(entry.getValue(), actual,
                        "Complete predecessor classes at N=" + n + ", gain=" + key.gain + ", child=" + key.key);
                checkedChildren++;
            }
        }
        assertTrue(checkedChildren > 20, "Exercise both restoration depths across many child classes");
        assertTrue(evenCycleFirstMoves[0] > 0,
                "Qualifying first supports splitting a single even-length cycle must be included");
    }

    @Test
    void phaseBoundariesRejectWrongDomainsAndUncontractedChildren() {
        final CyclicTargetPair small = pair(new int[]{1, 2, 0}, new int[]{0, 1, 2});
        assertThrows(IllegalArgumentException.class, () -> JointInverseReconstruction.intermediates(small, ignored -> { }));
        assertThrows(IllegalArgumentException.class, () -> JointInverseReconstruction.roots(small, ignored -> { }));
        assertThrows(IllegalArgumentException.class, () -> JointInverseReconstruction.preimages(small, 3, ignored -> { }));
        final CyclicTargetPair withFixedPoint = pair(new int[]{0, 2, 3, 1}, new int[]{0, 1, 2, 3});
        assertThrows(IllegalArgumentException.class,
                () -> JointInverseReconstruction.preimages(withFixedPoint, 1, ignored -> { }));
        final CyclicTargetPair withOmittedFixedPoint = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(1, 2, 3)), Cycle.of(0, 1, 2, 3));
        assertThrows(IllegalArgumentException.class,
                () -> JointInverseReconstruction.preimages(withOmittedFixedPoint, 1, ignored -> { }));
    }

    private static CyclicTargetPair contract(final int[] omega, final int[] beta) {
        final int[] labels = new int[omega.length];
        Arrays.fill(labels, -1);
        int size = 0;
        for (int x = 0; x < omega.length; x++) if (omega[x] != x) labels[x] = size++;
        final int[] contractedOmega = new int[size];
        for (int x = 0; x < omega.length; x++) {
            if (labels[x] >= 0) contractedOmega[labels[x]] = labels[omega[x]];
        }
        final int[] contractedBeta = new int[size];
        int index = 0;
        for (final int x : beta) if (labels[x] >= 0) contractedBeta[index++] = labels[x];
        return pair(contractedOmega, contractedBeta);
    }

    private static boolean fullTarget(final int[] omega) {
        final boolean[] seen = new boolean[omega.length];
        int x = 0;
        for (int i = 0; i < omega.length; i++) {
            if (seen[x]) return false;
            seen[x] = true;
            x = omega[(x + 1) % omega.length];
        }
        return x == 0;
    }

    /** A cycle is unoriented exactly when its successor is the previous support symbol. */
    private static boolean totallyUnoriented(final int[] omega) {
        final boolean[] seen = new boolean[omega.length];
        for (int first = 0; first < omega.length; first++) {
            if (seen[first]) continue;
            final int[] support = new int[omega.length];
            int size = 0;
            int x = first;
            do { support[size++] = x; seen[x] = true; x = omega[x]; } while (x != first);
            Arrays.sort(support, 0, size);
            for (int i = 0; i < size; i++) {
                if (omega[support[i]] != support[(i + size - 1) % size]) return false;
            }
        }
        return true;
    }

    private static int oddCycles(final int[] omega) {
        final boolean[] seen = new boolean[omega.length];
        int odd = 0;
        for (int first = 0; first < omega.length; first++) {
            if (seen[first]) continue;
            int size = 0;
            int x = first;
            do { seen[x] = true; size++; x = omega[x]; } while (x != first);
            odd += size % 2;
        }
        return odd;
    }

    private static boolean sameEvenCycle(final int[] omega, final int a, final int b, final int c) {
        int size = 0;
        int x = a;
        boolean hasB = false;
        boolean hasC = false;
        do { size++; hasB |= x == b; hasC |= x == c; x = omega[x]; } while (x != a);
        return size % 2 == 0 && hasB && hasC;
    }

    private static CyclicTargetPair pair(final int[] images, final int[] beta) {
        final MulticyclePermutation omega = new MulticyclePermutation();
        final boolean[] seen = new boolean[images.length];
        for (int first = 0; first < images.length; first++) {
            if (seen[first]) continue;
            final int[] symbols = new int[images.length];
            int size = 0;
            int x = first;
            do { symbols[size++] = x; seen[x] = true; x = images[x]; } while (x != first);
            omega.add(Cycle.of(Arrays.copyOf(symbols, size)));
        }
        return CyclicTargetPair.of(omega, Cycle.of(beta));
    }

    private static void permutations(final int[] values, final int at, final Consumer<int[]> consumer) {
        if (at == values.length) { consumer.accept(values); return; }
        for (int i = at; i < values.length; i++) {
            final int value = values[at]; values[at] = values[i]; values[i] = value;
            permutations(values, at + 1, consumer);
            values[i] = values[at]; values[at] = value;
        }
    }
}
