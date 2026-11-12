package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.ExtendedToricClassKey;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.reduction.Partitions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class JointRootGeneratorTest {
    @Test
    void completeClassSetsAgreeWithUnconstrainedPermutationEnumerationThroughTen() {
        for (int n = 3; n <= 10; n++) {
            final int[] beta = IntStream.range(0, n).toArray();
            final Set<ExtendedToricClassKey> expected = new HashSet<>();
            permutations(beta.clone(), 0, omega -> {
                for (int x = 0; x < omega.length; x++) if (omega[x] == x) return;
                if (!fullTarget(omega) || !leastNumericDihedralImage(omega)) return;
                final var analysis = JointReductionAnalysis.analyze(omega, beta);
                if (JointReductionAnalysis.isTotallyUnoriented(omega, beta)
                        || analysis.hasFavorablePrefix()) return;
                assertTrue(expected.add(decode(omega).extendedToricClassKey()));
            });
            final Set<ExtendedToricClassKey> actual = new HashSet<>();
            for (final int[] partition : Partitions.generateValidPartitions(n)) {
                JointRootGenerator.generateResiduals(partition, pair -> {
                    final var outcome = JointReductionAnalysis.analyze(pair);
                    assertFalse(outcome.hasFavorablePrefix());
                    assertNotEquals(JointReductionAnalysis.Route.UNORIENTED_ALL_ODD, outcome.route());
                    assertTrue(actual.add(pair.extendedToricClassKey()), "Duplicate generated class");
                });
            }
            assertEquals(expected, actual, "Complete class sets at N=" + n);
        }
    }

    @Test
    void unorientedResidualClassSetsAgreeWithUnprunedPermutationsThroughNine() {
        for (int n = 3; n <= 9; n++) {
            final int[] beta = IntStream.range(0, n).toArray();
            final Set<ExtendedToricClassKey> expected = new HashSet<>();
            permutations(beta.clone(), 0, omega -> {
                for (int x = 0; x < omega.length; x++) if (omega[x] == x) return;
                if (!fullTarget(omega) || !leastNumericDihedralImage(omega)
                        || !JointReductionAnalysis.isTotallyUnoriented(omega, beta)) return;
                if (!JointReductionAnalysis.analyze(omega, beta).hasFavorablePrefix())
                    assertTrue(expected.add(decode(omega).extendedToricClassKey()));
            });
            final Set<ExtendedToricClassKey> actual = new HashSet<>();
            for (final int[] partition : Partitions.generateValidPartitions(n)) {
                final Set<ExtendedToricClassKey> serial = new HashSet<>();
                JointRootGenerator.generateUnorientedResiduals(partition, pair -> {
                    assertFalse(JointReductionAnalysis.analyze(pair).hasFavorablePrefix());
                    assertTrue(serial.add(pair.extendedToricClassKey()));
                    assertTrue(actual.add(pair.extendedToricClassKey()));
                });
                assertEquals(serial.size(), JointRootGenerator.countUnorientedResiduals(partition));
                final Set<ExtendedToricClassKey> parallel = java.util.concurrent.ConcurrentHashMap.newKeySet();
                long counted = JointRootGenerator.generateUnorientedResidualsConcurrently(partition, 4, () -> {},
                        pair -> assertTrue(parallel.add(pair.extendedToricClassKey())));
                assertEquals(serial, parallel);
                assertEquals(serial.size(), counted);
            }
            assertEquals(expected, actual, "Complete unoriented residual scope at N=" + n);
        }
    }

    @Test
    void nineteenCycleHasExactly667ResidualClassesIncludingTwoWithNoFirstMove() {
        final Set<ExtendedToricClassKey> keys = new HashSet<>();
        final int[] noFirst = {0};
        JointRootGenerator.generateResiduals(new int[]{19}, pair -> {
            assertTrue(keys.add(pair.extendedToricClassKey()), "Duplicate 19-cycle class");
            final var outcome = JointReductionAnalysis.analyze(pair);
            assertFalse(outcome.hasFavorablePrefix());
            assertNotEquals(JointReductionAnalysis.Route.UNORIENTED_ALL_ODD, outcome.route());
            if (outcome.route() == JointReductionAnalysis.Route.NO_QUALIFYING_FIRST_MOVE) noFirst[0]++;
            assertFalse(SupportClearing.closes(pair));
        });
        assertEquals(667, keys.size());
        assertEquals(2, noFirst[0]);
    }

    @Test
    void countModeAgreesWithEmissionAndCanonicalizesPartitionOrder() {
        final int[][] partitions = {{3, 3, 3}, {5, 2, 2}, {4, 3, 2}, {3, 2, 2}};
        for (final int[] partition : partitions) {
            final long[] emitted = {0};
            JointRootGenerator.generateResiduals(partition, ignored -> emitted[0]++);
            final var count = JointRootGenerator.count(partition);
            assertEquals(emitted[0], count.jointResidualClasses());
            assertEquals(count.jointResidualClasses(), count.orientedClasses());
            assertEquals(0, count.supportClearingClasses());
            final int[] reversed = IntStream.range(0, partition.length)
                    .map(i -> partition[partition.length - 1 - i]).toArray();
            assertEquals(count, JointRootGenerator.count(reversed));
        }
        assertThrows(IllegalArgumentException.class, () -> JointRootGenerator.count(new int[]{1, 3}));
        assertThrows(IllegalArgumentException.class, () -> JointRootGenerator.count(new int[]{20}));
        assertThrows(IllegalArgumentException.class, () -> JointRootGenerator.count(new int[]{}));
        assertThrows(IllegalStateException.class, () -> JointRootGenerator.count(new int[]{19},
                () -> { throw new IllegalStateException("cancelled"); }));
    }

    @Test
    void interruptedCountingCancelsBeforeGeneration() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                    () -> JointRootGenerator.count(new int[]{19}));
        } finally {
            Thread.interrupted();
        }
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

    /** Full orbit comparison with every origin, independent of generator prefixes. */
    private static boolean leastNumericDihedralImage(final int[] omega) {
        final int n = omega.length;
        final int[] inverse = new int[n];
        for (int x = 0; x < n; x++) inverse[omega[x]] = x;
        for (int anchor = 0; anchor < n; anchor++) {
            for (int direction : new int[]{1, -1}) {
                for (int x = 0; x < n; x++) {
                    final int value = direction == 1
                            ? Math.floorMod(omega[(x + anchor) % n] - anchor, n)
                            : Math.floorMod(anchor - inverse[Math.floorMod(anchor - x, n)], n);
                    if (value < omega[x]) return false;
                    if (value > omega[x]) break;
                }
            }
        }
        return true;
    }

    private static CyclicTargetPair decode(final int[] omega) {
        final var cycles = new ArrayList<Cycle>();
        final boolean[] seen = new boolean[omega.length];
        for (int first = 0; first < omega.length; first++) {
            if (seen[first]) continue;
            final int[] symbols = new int[omega.length];
            int size = 0;
            int x = first;
            do {
                symbols[size++] = x;
                seen[x] = true;
                x = omega[x];
            } while (x != first);
            cycles.add(Cycle.of(Arrays.copyOf(symbols, size)));
        }
        return CyclicTargetPair.of(new MulticyclePermutation(cycles),
                Cycle.of(IntStream.range(0, omega.length).toArray()));
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
