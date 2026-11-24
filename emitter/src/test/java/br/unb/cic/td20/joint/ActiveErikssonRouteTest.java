package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.ExtendedToricClassKey;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.reduction.Partitions;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ActiveErikssonRouteTest {

    private record State(int[] current, int[] target, int fixedMask) {
    }

    @Test
    void completeClassesAgreeWithIndependentMovesOnBothCyclesThroughEight() throws Exception {
        for (int n = 3; n <= 8; n++) {
            final int[] beta = IntStream.range(0, n).toArray();
            final Set<ExtendedToricClassKey> expected = new HashSet<>();
            permutations(beta.clone(),
                    0,
                    omega -> {
                        for (int x = 0; x < omega.length; x++) {
                            if (omega[x] == x) {
                                return;
                            }
                        }

                        val target = new int[omega.length];
                        int x = 0, mask = 0;
                        for (int i = 0; i < omega.length; i++) {
                            if ((mask & (1 << x)) != 0) {
                                return;
                            }

                            mask |= 1 << x;
                            target[i] = x;
                            x = omega[(x + 1) % omega.length];
                        }
                        if (x != 0) {
                            return;
                        }

                        val root = new State(beta, target, 0);
                        final boolean independent = prefix(root);
                        assertEquals(independent, ErikssonThirdMoveAnalysis.closesOrHasPrefix(omega, beta));
                        if (!independent) {
                            expected.add(decode(omega).extendedToricClassKey());
                        }
                    });

            final Set<ExtendedToricClassKey> actual = java.util.concurrent.ConcurrentHashMap.newKeySet();
            for (final int[] partition : Partitions.generateValidPartitions(n)) {
                val counts = ErikssonRootGenerator.generate(partition,
                        2,
                        () -> {
                        },
                        omega -> assertTrue(actual.add(omega.extendedToricClassKey()), "Duplicate root class"));
                assertTrue(counts.direct() >= 0);
            }

            assertEquals(expected, actual, "All two-sided root classes at N=" + n);
        }
    }

    @Test
    void parallelBranchesAgreeAndReverseExceptionIsClosed() throws Exception {
        for (final int[] partition : new int[][] { { 7 }, { 5, 2, 2 }, { 4, 3, 2 } }) {
            val serial = ErikssonRootGenerator.count(partition, 1, new LongAdder());
            val parallel = ErikssonRootGenerator.count(partition, 4, new LongAdder());
            assertEquals(serial.direct(), parallel.direct());
            assertEquals(serial.unoriented(), parallel.unoriented());
            assertEquals(serial.oriented(), parallel.oriented());
        }

        final int n = ErikssonRoute.ROOT_SYMBOLS;
        final int[] beta = IntStream.range(0, n).toArray();
        final int[] reverse = IntStream.range(0, n).map(x -> (x + n - 2) % n).toArray();
        assertTrue(ErikssonThirdMoveAnalysis.closesOrHasPrefix(reverse, beta));
        assertThrows(IllegalArgumentException.class,
                () -> ErikssonThirdMoveAnalysis.closesOrHasPrefix(new int[] { 2, 0, 1 }, new int[] { 0, 1, 2 }));
    }

    // The oracle changes the current/target cycles themselves, enumerating all
    // supports. It neither swaps omega with its inverse nor uses fast triples.
    private static boolean prefix(final State root) {
        final List<State> firsts = moves(root);
        for (final State first : firsts) {
            if (Integer.bitCount(first.fixedMask) >= 2) {
                return true;
            }
        }
        for (final State first : firsts) {
            for (final State second : moves(first)) {
                final int fixed = Integer.bitCount(second.fixedMask);
                if (fixed >= 4) {
                    return true;
                }
                if (fixed != 3) {
                    continue;
                }
                for (final State third : moves(second)) {
                    if (Integer.bitCount(third.fixedMask) >= 5) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static List<State> moves(final State state) {
        final List<State> result = new ArrayList<>();
        final int n = state.current.length;
        for (int side = 0; side < 2; side++) {
            final int[] cycle = side == 0 ? state.current : state.target;
            for (int i = 0; i < n - 2; i++) {
                for (int j = i + 1; j < n - 1; j++) {
                    for (int k = j + 1; k < n; k++) {
                        final int a = cycle[i], b = cycle[j], c = cycle[k];
                        val next = new int[n];
                        for (int t = 0; t < n; t++) {
                            final int y = cycle[(t + 1) % n];
                            next[cycle[t]] = y == a ? b : y == b ? c : y == c ? a : y;
                        }

                        val changed = new int[n];
                        int x = cycle[0];
                        for (int t = 0; t < n; t++) {
                            changed[t] = x;
                            x = next[x];
                        }

                        final int[] current = side == 0 ? changed : state.current, target = side == 0 ? state.target : changed;
                        final int[] currentNext = new int[n], targetNext = new int[n];
                        for (int t = 0; t < n; t++) {
                            currentNext[current[t]] = current[(t + 1) % n];
                            targetNext[target[t]] = target[(t + 1) % n];
                        }

                        int mask = 0;
                        for (int t = 0; t < n; t++) {
                            if (currentNext[t] == targetNext[t]) {
                                mask |= 1 << currentNext[t];
                            }
                        }
                        // Existing fixed points are contracted in the implementation.
                        if ((mask & state.fixedMask) != state.fixedMask) {
                            continue;
                        }

                        result.add(new State(current, target, mask));
                    }
                }
            }
        }

        return result;
    }

    private static CyclicTargetPair decode(final int[] omega) {
        val permutation = new MulticyclePermutation();
        val seen = new boolean[omega.length];
        for (int x = 0; x < omega.length; x++) {
            if (!seen[x]) {
                final List<Integer> cycle = new ArrayList<>();
                int y = x;
                do {
                    seen[y] = true;
                    cycle.add(y);
                    y = omega[y];
                } while (y != x);

                permutation.add(Cycle.of(cycle.stream().mapToInt(Integer::intValue).toArray()));
            }
        }

        return CyclicTargetPair.ofOwned(permutation, Cycle.of(IntStream.range(0, omega.length).toArray()));
    }

    private static void permutations(final int[] omega, final int start, final Consumer<int[]> sink) {
        if (start == omega.length) {
            sink.accept(omega);
            return;
        }
        for (int j = start; j < omega.length; j++) {
            int value = omega[start];
            omega[start] = omega[j];
            omega[j] = value;
            permutations(omega, start + 1, sink);
            value = omega[start];
            omega[start] = omega[j];
            omega[j] = value;
        }
    }
}
