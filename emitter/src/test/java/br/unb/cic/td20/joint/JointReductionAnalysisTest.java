package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetCandidate;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.search.CommonOperations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static br.unb.cic.td20.joint.JointReductionAnalysis.*;
import static org.junit.jupiter.api.Assertions.*;

class JointReductionAnalysisTest {
    @Test
    void consecutiveTriplesAgreeWithEveryTranspositionThroughEightSymbols() {
        final int[] checked = {0};
        for (int n = 3; n <= 8; n++) {
            final int[] beta = IntStream.range(0, n).toArray();
            permutations(beta.clone(), 0, omega -> {
                if (fixedCount(omega) != 0) return;
                final Set<String> expected = new HashSet<>();
                for (final Move move : applicableMoves(beta, omega, false)) {
                    if (fixedCount(multiplyOmega(omega, move)) >= 2) {
                        expected.add(support(move));
                    }
                }
                final var fast = fastMoves(omega, beta);
                final Set<String> actual = new HashSet<>();
                for (final Move move : fast) actual.add(support(move));
                assertEquals(expected, actual, () -> Arrays.toString(omega));
                assertEquals(actual.size(), fast.size(), "Duplicate fast transposition");
                assertEquals(!expected.isEmpty(), hasFastMove(omega, beta));
                checked[0]++;
            });
        }
        assertEquals(17_007, checked[0]);
    }

    @Test
    void jointClassificationAgreesWithUncontractedExhaustiveSearch() {
        final Set<Route> observed = new HashSet<>();
        final int[] valid = {0};
        for (int n = 3; n <= 8; n++) {
            final int[] beta = IntStream.range(0, n).toArray();
            permutations(beta.clone(), 0, omega -> {
                if (fixedCount(omega) != 0 || !fullCycle(target(omega, beta))) return;
                valid[0]++;
                for (final boolean stop : List.of(false, true)) {
                    final Result actual = analyze(omega, beta, stop);
                    final Expected expected = exhaustive(omega, beta, stop);
                    assertEquals(expected.route, actual.route(),
                            () -> Arrays.toString(omega) + " stop=" + stop);
                    assertEquals(expected.qualifying, actual.qualifyingFirstMoves());
                    assertEquals(expected.stopped, actual.stoppedSuccessors());
                    assertEquals(expected.favorableFirsts, actual.favorableFirstMoves());
                    assertEquals(expected.favorablePairs, actual.favorablePairs());
                    replayPrefix(omega, beta, actual);
                    observed.add(actual.route());
                }
            });
        }
        assertTrue(valid[0] > 100);
        assertTrue(observed.contains(Route.TOTALLY_UNORIENTED));
        assertTrue(observed.contains(Route.IMMEDIATE_AT_LEAST_TWO));
        assertTrue(observed.contains(Route.NO_QUALIFYING_FIRST_MOVE));
        assertTrue(observed.contains(Route.ONE_THEN_TWO));
    }

    @Test
    void jointSelectionPrefersASecondMoveCreatingThreeFixedPoints() {
        final int[] omega = {3, 5, 0, 1, 7, 2, 8, 4, 6};
        final int[] beta = IntStream.range(0, omega.length).toArray();
        final Result actual = analyze(omega, beta);
        assertEquals(Route.ONE_THEN_THREE, actual.route());
        assertEquals(exhaustive(omega, beta, true).favorablePairs, actual.favorablePairs());
        replayPrefix(omega, beta, actual);
    }

    @Test
    void nineteenCyclePowersHaveNoQualifyingFirstMove() {
        final int[][] survivors = {
                {0, 10, 1, 11, 2, 12, 3, 13, 4, 14, 5, 15, 6, 16, 7, 17, 8, 18, 9},
                {0, 16, 13, 10, 7, 4, 1, 17, 14, 11, 8, 5, 2, 18, 15, 12, 9, 6, 3}
        };
        for (final int[] cycle : survivors) {
            final var pair = new CyclicTargetCandidate(
                    new MulticyclePermutation(Cycle.of(cycle)),
                    Cycle.of(IntStream.range(0, 19).toArray())).requireCyclicTargetPair();
            final Result result = analyze(pair);
            assertEquals(Route.NO_QUALIFYING_FIRST_MOVE, result.route());
            assertEquals(0, result.qualifyingFirstMoves());
            assertFalse(result.hasFavorablePrefix());
            assertTrue(result.prefix().isEmpty());
        }
    }

    @Test
    void contractionPreservesTheTargetAfterDeletionAndOwnsItsArrays() {
        final int[] omega = {0, 3, 2, 4, 1};
        final int[] beta = {2, 0, 4, 1, 3};
        final Contracted contracted = contractFixedPoints(omega, beta);
        assertArrayEquals(new int[]{1, 3, 4}, contracted.originalLabels());
        assertArrayEquals(new int[]{1, 2, 0}, contracted.omega());
        assertArrayEquals(new int[]{2, 0, 1}, contracted.beta());
        final int[] labels = contracted.originalLabels();
        final int[] originalTarget = target(omega, beta);
        final int[] reducedTarget = target(contracted.omega(), contracted.beta());
        for (int x = 0; x < labels.length; x++) {
            int image = originalTarget[labels[x]];
            while (omega[image] == image) image = originalTarget[image];
            assertEquals(image, labels[reducedTarget[x]]);
        }
        contracted.omega()[0] = 0;
        contracted.beta()[0] = 0;
        contracted.originalLabels()[0] = 0;
        assertArrayEquals(new int[]{1, 2, 0}, contracted.omega());
        assertArrayEquals(new int[]{2, 0, 1}, contracted.beta());
        assertArrayEquals(new int[]{1, 3, 4}, contracted.originalLabels());
    }

    @Test
    void analysisRejectsUncheckedTargetsAndUncontractedFixedPoints() {
        assertThrows(IllegalArgumentException.class,
                () -> analyze(new int[]{1, 0}, new int[]{0, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> analyze(new int[]{0, 2, 3, 1}, new int[]{0, 1, 2, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> fastMoves(new int[]{0, 2, 3, 1}, new int[]{0, 1, 2, 3}));
    }

    @Test
    void cyclicRelabelingAndTwistedReflectionPreserveTheOutcome() {
        final int[] checked = {0};
        final int n = 7;
        permutations(IntStream.range(0, n).toArray(), 0, omega -> {
            final int[] beta = IntStream.range(0, n).toArray();
            if (fixedCount(omega) != 0 || !fullCycle(target(omega, beta))) return;
            final Result original = analyze(omega, beta);
            final int[] rotated = new int[n];
            final int[] reflected = new int[n];
            final int[] inverse = inverse(omega);
            for (int x = 0; x < n; x++) {
                rotated[(x + 2) % n] = (omega[x] + 2) % n;
                reflected[Math.floorMod(-x, n)] = Math.floorMod(-inverse[x], n);
            }
            assertEquals(original.route(), analyze(rotated, beta).route());
            assertEquals(original.route(), analyze(reflected, beta).route());
            checked[0]++;
        });
        assertTrue(checked[0] > 100);
    }

    private record Expected(Route route, int qualifying, int stopped,
                            int favorableFirsts, int favorablePairs) { }

    /** No consecutive-triple criterion and no contraction is used here. */
    private static Expected exhaustive(final int[] omega, final int[] beta, final boolean stop) {
        if (stop && allUnoriented(omega, beta)) {
            return new Expected(Route.TOTALLY_UNORIENTED, 0, 0, 0, 0);
        }
        final var firsts = applicableMoves(beta, omega, false);
        for (final Move first : firsts) {
            if (fixedCount(multiplyOmega(omega, first)) >= 2) {
                return new Expected(Route.IMMEDIATE_AT_LEAST_TWO, 0, 0, 0, 0);
            }
        }
        int qualifying = 0, stopped = 0, favorableFirsts = 0, favorablePairs = 0, best = 0;
        for (final Move first : firsts) {
            final int[] nextOmega = multiplyOmega(omega, first);
            if (fixedCount(nextOmega) != 1 || oddCycles(nextOmega) != oddCycles(omega) + 2) continue;
            qualifying++;
            final int[] nextBeta = CommonOperations.applyTranspositionOptimized(
                    Cycle.of(beta.clone()), Cycle.of(first.a(), first.b(), first.c())).getSymbols();
            if (stop && allUnoriented(nextOmega, nextBeta)) { stopped++; continue; }
            int successful = 0;
            for (final Move second : applicableMoves(nextBeta, nextOmega, true)) {
                final int gain = fixedCount(multiplyOmega(nextOmega, second)) - 1;
                if (gain >= 2) { successful++; best = Math.max(best, gain); }
            }
            favorablePairs += successful;
            if (successful > 0) favorableFirsts++;
        }
        final Route route = best == 3 ? Route.ONE_THEN_THREE : best == 2 ? Route.ONE_THEN_TWO
                : qualifying == 0 ? Route.NO_QUALIFYING_FIRST_MOVE : Route.RETAINED;
        return new Expected(route, qualifying, stopped, favorableFirsts, favorablePairs);
    }

    private static void replayPrefix(final int[] omega, final int[] beta, final Result result) {
        int[] currentOmega = omega.clone(), currentBeta = beta.clone();
        final int[] rho = target(omega, beta);
        int index = 0;
        for (final Move move : result.prefix()) {
            final int before = fixedCount(currentOmega);
            final int[] expectedOmega = multiplyOmega(currentOmega, move);
            final int[] expectedBeta = CommonOperations.applyTranspositionOptimized(
                    Cycle.of(currentBeta.clone()), Cycle.of(move.a(), move.b(), move.c())).getSymbols();
            currentOmega = applyToOmega(currentOmega, move);
            currentBeta = applyToBeta(currentBeta, move);
            assertArrayEquals(expectedOmega, currentOmega);
            assertEquals(Cycle.of(expectedBeta), Cycle.of(currentBeta));
            assertArrayEquals(rho, target(currentOmega, currentBeta));
            assertEquals(index++ == 0 ? result.firstMoveGain() : result.secondMoveGain(),
                    fixedCount(currentOmega) - before);
        }
        assertThrows(UnsupportedOperationException.class,
                () -> result.prefix().add(new Move(0, 1, 2)));
    }

    private static List<Move> applicableMoves(final int[] beta, final int[] omega,
                                             final boolean excludeFixed) {
        final List<Move> result = new ArrayList<>();
        for (int i = 0; i < beta.length - 2; i++) {
            for (int j = i + 1; j < beta.length - 1; j++) {
                for (int k = j + 1; k < beta.length; k++) {
                    final int a = beta[i], b = beta[j], c = beta[k];
                    if (excludeFixed && (omega[a] == a || omega[b] == b || omega[c] == c)) continue;
                    result.add(new Move(a, b, c));
                }
            }
        }
        return result;
    }

    private static int[] multiplyOmega(final int[] omega, final Move move) {
        final int[] inverseTau = IntStream.range(0, omega.length).toArray();
        inverseTau[move.a()] = move.c();
        inverseTau[move.b()] = move.a();
        inverseTau[move.c()] = move.b();
        final int[] result = new int[omega.length];
        for (int x = 0; x < result.length; x++) result[x] = omega[inverseTau[x]];
        return result;
    }

    private static int[] target(final int[] omega, final int[] beta) {
        final int[] result = new int[omega.length];
        for (int i = 0; i < beta.length; i++) result[beta[i]] = omega[beta[(i + 1) % beta.length]];
        return result;
    }

    private static int fixedCount(final int[] omega) {
        int count = 0;
        for (int x = 0; x < omega.length; x++) if (omega[x] == x) count++;
        return count;
    }

    private static List<int[]> cycles(final int[] omega) {
        final Set<Integer> remaining = new HashSet<>();
        for (int x = 0; x < omega.length; x++) remaining.add(x);
        final List<int[]> cycles = new ArrayList<>();
        while (!remaining.isEmpty()) {
            final List<Integer> cycle = new ArrayList<>();
            int x = remaining.iterator().next();
            while (remaining.remove(x)) { cycle.add(x); x = omega[x]; }
            cycles.add(cycle.stream().mapToInt(Integer::intValue).toArray());
        }
        return cycles;
    }

    private static int oddCycles(final int[] omega) {
        return (int) cycles(omega).stream().filter(cycle -> cycle.length % 2 == 1).count();
    }

    private static boolean allUnoriented(final int[] omega, final int[] beta) {
        return cycles(omega).stream().noneMatch(cycle -> cycle.length >= 3
                && CommonOperations.isOriented(Cycle.of(beta.clone()), Cycle.of(cycle)));
    }

    private static boolean fullCycle(final int[] permutation) {
        return cycles(permutation).size() == 1;
    }

    private static int[] inverse(final int[] permutation) {
        final int[] inverse = new int[permutation.length];
        for (int x = 0; x < permutation.length; x++) inverse[permutation[x]] = x;
        return inverse;
    }

    private static String support(final Move move) {
        final int[] labels = {move.a(), move.b(), move.c()};
        Arrays.sort(labels);
        return Arrays.toString(labels);
    }

    private static void permutations(final int[] values, final int at, final Consumer<int[]> consumer) {
        if (at == values.length) { consumer.accept(values); return; }
        for (int i = at; i < values.length; i++) {
            final int tmp = values[at]; values[at] = values[i]; values[i] = tmp;
            permutations(values, at + 1, consumer);
            values[i] = values[at]; values[at] = tmp;
        }
    }
}
