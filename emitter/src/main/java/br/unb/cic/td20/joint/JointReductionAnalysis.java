package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Complete joint selection of up to three sorting transpositions. Inputs are
 * fixed-point-free valid cyclic-target pairs on labels 0 through N-1.
 * The current cycle beta is a cyclic writing; omega is an image array.
 *
 * <p>The selection first seeks a move creating at least two fixed points.
 * Otherwise every qualifying gain-one move is considered. Gain-three second
 * moves close, while gain-one and gain-two seconds continue to a third move.
 * Favorable third-depth patterns are (1,1,3), (1,2,2), and (1,2,3). A qualifying
 * move increases the number of odd-length algebraic cycles by two. Totally
 * unoriented pairs receive no structural reduction at either depth.</p>
 *
 * <p>A move creating two fixed points uses consecutive algebraic symbols
 * x, omega(x), omega squared(x). Their order in beta finds every such move,
 * including supports on even-length cycles, in at most N order tests.
 * The optional boolean overloads expose the order predicates for diagnostic
 * comparison; structural selection uses the default unoriented stop.</p>
 */
public final class JointReductionAnalysis {
    private JointReductionAnalysis() { }

    public enum Route {
        TOTALLY_UNORIENTED,
        IMMEDIATE_AT_LEAST_TWO,
        ONE_THEN_THREE,
        ONE_ONE_THEN_THREE,
        ONE_TWO_THEN_TWO,
        ONE_TWO_THEN_THREE,
        NO_QUALIFYING_FIRST_MOVE,
        RETAINED
    }

    /** An applicable algebraic 3-cycle representing one sorting transposition. */
    public record Move(int a, int b, int c) {
        public Move {
            if (a < 0 || b < 0 || c < 0 || a == b || b == c || c == a) {
                throw new IllegalArgumentException(
                        "A transposition requires three distinct nonnegative labels");
            }
        }
    }

    /**
     * Counts concern applicable moves, not pair classes or sorting witnesses.
     * The prefix uses the original input labels, even after intermediate
     * contraction. Immediate outcomes do not enumerate first moves of gain one.
     */
    public record Result(Route route, List<Move> prefix,
                         int firstMoveGain, int secondMoveGain,
                         int fastFirstMoves, int qualifyingFirstMoves,
                         int stoppedSuccessors, int favorableFirstMoves,
                         int favorablePairs) {
        public Result {
            Objects.requireNonNull(route);
            prefix = List.copyOf(prefix);
        }

        public boolean hasFavorablePrefix() {
            return !prefix.isEmpty();
        }
    }

    /** A contracted pair and the injective map from its labels to input labels. */
    public record Contracted(int[] omega, int[] beta, int[] originalLabels) {
        public Contracted {
            omega = omega.clone();
            beta = beta.clone();
            originalLabels = originalLabels.clone();
        }

        @Override public int[] omega() { return omega.clone(); }
        @Override public int[] beta() { return beta.clone(); }
        @Override public int[] originalLabels() { return originalLabels.clone(); }
    }

    public static Result analyze(final CyclicTargetPair pair) {
        return analyze(pair, true);
    }

    public static Result analyze(final CyclicTargetPair pair,
                                 final boolean stopTotallyUnoriented) {
        Objects.requireNonNull(pair);
        final int[] omega = new int[pair.getBeta().size()];
        for (int x = 0; x < omega.length; x++) {
            omega[x] = pair.getOmega().image(x);
        }
        return analyze(omega, pair.getBeta().getSymbols(), stopTotallyUnoriented);
    }

    public static Result analyze(final int[] omega, final int[] beta) {
        return analyze(omega, beta, true);
    }

    public static Result analyze(final int[] omega, final int[] beta,
                                 final boolean stopTotallyUnoriented) {
        validateFixedPointFree(omega, beta);
        if (!isFullTargetCycle(omega, beta)) {
            throw new IllegalArgumentException(
                    "omega * beta must be a full cycle before analysis");
        }
        if (stopTotallyUnoriented && isTotallyUnorientedUnchecked(omega, beta)) {
            return empty(Route.TOTALLY_UNORIENTED);
        }
        final List<Move> immediate = fastMovesUnchecked(omega, beta);
        if (!immediate.isEmpty()) {
            final Move first = immediate.getFirst();
            return new Result(Route.IMMEDIATE_AT_LEAST_TWO, List.of(first),
                    gain(omega, first), 0, immediate.size(), 0, 0, 0, 0);
        }

        final int oddBefore = oddCycleCount(omega);
        int qualifying = 0;
        int stopped = 0;
        int favorableFirsts = 0;
        int favorablePrefixes = 0;
        for (int i = 0; i < beta.length - 2; i++) {
            for (int j = i + 1; j < beta.length - 1; j++) {
                for (int k = j + 1; k < beta.length; k++) {
                    final Move first = new Move(beta[i], beta[j], beta[k]);
                    if (gain(omega, first) != 1) continue;
                    final int[] successorOmega = applyToOmega(omega, first);
                    if (oddCycleCount(successorOmega) != oddBefore + 2) continue;
                    qualifying++;
                    final int[] successorBeta = applyToBeta(beta, first);
                    final Contracted contracted = contractFixedPointsUnchecked(
                            successorOmega, successorBeta);
                    if (stopTotallyUnoriented && isTotallyUnorientedUnchecked(
                            contracted.omega, contracted.beta)) {
                        stopped++;
                        continue;
                    }
                    final int contractedOdd = oddCycleCount(contracted.omega);
                    for (int r = 0; r < contracted.beta.length - 2; r++) {
                        for (int s = r + 1; s < contracted.beta.length - 1; s++) {
                            for (int t = s + 1; t < contracted.beta.length; t++) {
                                final Move second = new Move(contracted.beta[r],
                                        contracted.beta[s], contracted.beta[t]);
                                final int secondGain = gain(contracted.omega, second);
                                if (secondGain < 1) continue;
                                final int[] secondOmega = applyToOmega(contracted.omega, second);
                                if (oddCycleCount(secondOmega) != contractedOdd + 2) continue;
                                final Move liftedSecond = lift(second, contracted.originalLabels);
                                if (secondGain == 3) {
                                    favorableFirsts++; favorablePrefixes++;
                                    return new Result(Route.ONE_THEN_THREE,
                                            List.of(first, liftedSecond), 1, 3, 0,
                                            qualifying, stopped, favorableFirsts, favorablePrefixes);
                                }
                                final int[] secondBeta = applyToBeta(contracted.beta, second);
                                final Contracted twice = contractFixedPointsUnchecked(secondOmega, secondBeta);
                                if (stopTotallyUnoriented && isTotallyUnorientedUnchecked(
                                        twice.omega, twice.beta)) {
                                    stopped++;
                                    continue;
                                }
                                for (final Move third : fastMovesUnchecked(twice.omega, twice.beta)) {
                                    final int thirdGain = gain(twice.omega, third);
                                    if (secondGain == 1 && thirdGain != 3
                                            || secondGain == 2 && thirdGain < 2) continue;
                                    favorableFirsts++; favorablePrefixes++;
                                    final Move liftedThird = new Move(
                                            contracted.originalLabels[twice.originalLabels[third.a]],
                                            contracted.originalLabels[twice.originalLabels[third.b]],
                                            contracted.originalLabels[twice.originalLabels[third.c]]);
                                    final Route route = secondGain == 1
                                            ? Route.ONE_ONE_THEN_THREE
                                            : thirdGain == 3 ? Route.ONE_TWO_THEN_THREE
                                            : Route.ONE_TWO_THEN_TWO;
                                    return new Result(route, List.of(first, liftedSecond, liftedThird),
                                            1, secondGain, 0, qualifying, stopped,
                                            favorableFirsts, favorablePrefixes);
                                }
                            }
                        }
                    }
                }
            }
        }
        final Route route = qualifying == 0 ? Route.NO_QUALIFYING_FIRST_MOVE : Route.RETAINED;
        return new Result(route, List.of(), 0, 0, 0, qualifying, stopped,
                favorableFirsts, favorablePrefixes);
    }

    private static Result empty(final Route route) {
        return new Result(route, List.of(), 0, 0, 0, 0, 0, 0, 0);
    }

    private static Move lift(final Move move, final int[] labels) {
        return new Move(labels[move.a], labels[move.b], labels[move.c]);
    }

    /**
     * All moves creating at least two fixed points. This algebraic test does
     * not require target realizability, but does require a fixed-point-free
     * omega. The returned immutable list contains each applicable support once.
     */
    public static List<Move> fastMoves(final int[] omega, final int[] beta) {
        validateFixedPointFree(omega, beta);
        return fastMovesUnchecked(omega, beta);
    }

    public static boolean hasFastMove(final int[] omega, final int[] beta) {
        return !fastMoves(omega, beta).isEmpty();
    }

    private static List<Move> fastMovesUnchecked(final int[] omega, final int[] beta) {
        final int[] positions = positions(beta);
        final List<Move> result = new ArrayList<>();
        for (int a = 0; a < omega.length; a++) {
            final int b = omega[a];
            final int c = omega[b];
            if (a == b || b == c || a == c) continue;
            // In a 3-cycle its three starting symbols describe the same move.
            if (omega[c] == a && (a > b || a > c)) continue;
            if (inCyclicOrder(positions, a, b, c)) result.add(new Move(a, b, c));
        }
        return List.copyOf(result);
    }

    public static boolean isTotallyUnoriented(final int[] omega, final int[] beta) {
        validateArrays(omega, beta);
        return isTotallyUnorientedUnchecked(omega, beta);
    }

    private static boolean isTotallyUnorientedUnchecked(
            final int[] omega, final int[] beta) {
        final int[] positions = positions(beta);
        final boolean[] seen = new boolean[omega.length];
        for (int start = 0; start < omega.length; start++) {
            if (seen[start]) continue;
            int length = 0;
            int increasingEdges = 0;
            int x = start;
            do {
                seen[x] = true;
                final int y = omega[x];
                if (positions[y] > positions[x]) increasingEdges++;
                length++;
                x = y;
            } while (x != start);
            // A cycle of length >=3 follows reverse beta order iff precisely
            // one of its directed edges increases the linear beta position.
            if (length >= 3 && increasingEdges != 1) return false;
        }
        return true;
    }

    public static int gain(final int[] omega, final Move move) {
        return (omega[move.c] == move.a ? 1 : 0)
                + (omega[move.a] == move.b ? 1 : 0)
                + (omega[move.b] == move.c ? 1 : 0);
    }

    /** Right multiplication omega * tau inverse; the input is unchanged. */
    public static int[] applyToOmega(final int[] omega, final Move move) {
        final int[] result = omega.clone();
        result[move.a] = omega[move.c];
        result[move.b] = omega[move.a];
        result[move.c] = omega[move.b];
        return result;
    }

    /** Left multiplication tau * beta, returned starting at beta's old anchor. */
    public static int[] applyToBeta(final int[] beta, final Move move) {
        final int[] pos = positions(beta);
        if (move.a >= beta.length || move.b >= beta.length || move.c >= beta.length
                || !inCyclicOrder(pos, move.a, move.b, move.c)) {
            throw new IllegalArgumentException(
                    "The transposition is not applicable to the current cycle");
        }
        final int[] next = new int[beta.length];
        for (int i = 0; i < beta.length; i++) {
            final int image = beta[(i + 1) % beta.length];
            next[beta[i]] = image == move.a ? move.b
                    : image == move.b ? move.c : image == move.c ? move.a : image;
        }
        final int[] result = new int[beta.length];
        int x = beta[0];
        for (int i = 0; i < result.length; i++) {
            result[i] = x;
            x = next[x];
        }
        if (x != beta[0]) throw new IllegalStateException(
                "Applicable transposition did not preserve the current cycle");
        return result;
    }

    public static Contracted contractFixedPoints(final int[] omega, final int[] beta) {
        validateArrays(omega, beta);
        return contractFixedPointsUnchecked(omega, beta);
    }

    private static Contracted contractFixedPointsUnchecked(
            final int[] omega, final int[] beta) {
        int n = 0;
        for (int x = 0; x < omega.length; x++) if (omega[x] != x) n++;
        final int[] originalLabels = new int[n];
        final int[] newLabels = new int[omega.length];
        Arrays.fill(newLabels, -1);
        int index = 0;
        for (int x = 0; x < omega.length; x++) {
            if (omega[x] != x) {
                originalLabels[index] = x;
                newLabels[x] = index++;
            }
        }
        final int[] contractedOmega = new int[n];
        final int[] contractedBeta = new int[n];
        for (int x = 0; x < n; x++) {
            contractedOmega[x] = newLabels[omega[originalLabels[x]]];
        }
        index = 0;
        for (final int x : beta) if (newLabels[x] >= 0) contractedBeta[index++] = newLabels[x];
        return new Contracted(contractedOmega, contractedBeta, originalLabels);
    }

    public static int oddCycleCount(final int[] omega) {
        final boolean[] seen = new boolean[omega.length];
        int odd = 0;
        for (int start = 0; start < omega.length; start++) {
            if (seen[start]) continue;
            int length = 0;
            int x = start;
            do { seen[x] = true; length++; x = omega[x]; } while (x != start);
            odd += length & 1;
        }
        return odd;
    }

    public static boolean isFullTargetCycle(final int[] omega, final int[] beta) {
        if (omega.length == 0) return true;
        final int[] target = new int[omega.length];
        for (int i = 0; i < beta.length; i++) {
            target[beta[i]] = omega[beta[(i + 1) % beta.length]];
        }
        int x = 0;
        int length = 0;
        do { x = target[x]; length++; } while (x != 0 && length < target.length);
        return x == 0 && length == target.length;
    }

    private static int[] positions(final int[] beta) {
        final int[] result = new int[beta.length];
        for (int i = 0; i < beta.length; i++) result[beta[i]] = i;
        return result;
    }

    private static boolean inCyclicOrder(final int[] positions,
                                         final int a, final int b, final int c) {
        final int n = positions.length;
        return Math.floorMod(positions[b] - positions[a], n)
                < Math.floorMod(positions[c] - positions[a], n);
    }

    private static void validateFixedPointFree(final int[] omega, final int[] beta) {
        validateArrays(omega, beta);
        for (int x = 0; x < omega.length; x++) {
            if (omega[x] == x) throw new IllegalArgumentException(
                    "Contract algebraic fixed points before joint reduction analysis");
        }
    }

    private static void validateArrays(final int[] omega, final int[] beta) {
        Objects.requireNonNull(omega);
        Objects.requireNonNull(beta);
        if (omega.length != beta.length || omega.length < 2) {
            throw new IllegalArgumentException(
                    "omega and beta must have the same domain of at least two symbols");
        }
        for (final int[] values : List.of(omega, beta)) {
            final boolean[] seen = new boolean[values.length];
            for (final int value : values) {
                if (value < 0 || value >= values.length || seen[value]) {
                    throw new IllegalArgumentException(
                            "Each input must be a permutation of labels 0 through N-1");
                }
                seen[value] = true;
            }
        }
    }
}
