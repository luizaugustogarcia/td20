package br.unb.cic.td20.joint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Image-array transpositions and fixed-point contraction for cyclic-target pairs. */
public final class CyclicTargetMoves {
    private CyclicTargetMoves() { }
    /** An applicable algebraic 3-cycle representing one sorting transposition. */
    public record Move(int a, int b, int c) {
        public Move {
            if (a < 0 || b < 0 || c < 0 || a == b || b == c || c == a) {
                throw new IllegalArgumentException(
                        "A transposition requires three distinct nonnegative labels");
            }
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

    /**
     * All moves creating at least two fixed points. This algebraic test does
     * not require target realizability, but does require a fixed-point-free
     * omega. The returned immutable list contains each applicable support once.
     */
    public static List<Move> fastMoves(final int[] omega, final int[] beta) {
        validateFixedPointFree(omega, beta);
        return fastMovesUnchecked(omega, beta);
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
                    "Contract algebraic fixed points before move enumeration");
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
