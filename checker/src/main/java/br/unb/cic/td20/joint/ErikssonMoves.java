package br.unb.cic.td20.joint;

import lombok.val;

import java.util.Arrays;

/**
 * Independent arbitrary-support enumeration on the two full cycles.
 */
public final class ErikssonMoves {

    private ErikssonMoves() {
    }

    private record State(int[] current, int[] target, int[] currentNext, int[] targetNext, int[] currentPrevious, int[] targetPrevious, int fixed) {
    }

    public static boolean closesOrHasPrefix(final int[] omega, final int[] beta) {
        final int n = omega.length;
        final int[] targetNext = new int[n], target = new int[n];
        if (n < 3 || n > ErikssonRoute.ROOT_SYMBOLS || beta.length != n) {
            throw new IllegalArgumentException("Invalid root domain");
        }

        int seenOmega = 0, seenBeta = 0;
        for (int i = 0; i < n; i++) {
            if (omega[i] < 0
                    || omega[i] >= n
                    || beta[i] < 0
                    || beta[i] >= n
                    || (seenOmega & (1 << omega[i])) != 0
                    || (seenBeta & (1 << beta[i])) != 0) {
                throw new IllegalArgumentException("Expected permutations of the full domain");
            }

            seenOmega |= 1 << omega[i];
            seenBeta |= 1 << beta[i];
        }
        for (int i = 0; i < n; i++) {
            targetNext[beta[i]] = omega[beta[(i + 1) % n]];
        }

        int x = 0, seenTarget = 0;
        for (int i = 0; i < n; i++) {
            if ((seenTarget & (1 << x)) != 0) {
                throw new IllegalArgumentException("Target is not a full cycle");
            }

            seenTarget |= 1 << x;
            target[i] = x;
            x = targetNext[x];
        }
        if (x != 0) {
            throw new IllegalArgumentException("Target does not close");
        }
        if (n == ErikssonRoute.ROOT_SYMBOLS && (n + 1) / 2 <= ErikssonRoute.ROOT_BUDGET) {
            boolean reverse = true;
            for (int i = 0; i < n; i++) {
                if (omega[beta[i]] != beta[(i + n - 2) % n]) {
                    reverse = false;
                    break;
                }
            }
            if (reverse) {
                return true;
            }
        }

        final State root = state(beta, target);
        if (root.fixed != 0) {
            throw new IllegalArgumentException("Contract fixed points before root selection");
        }
        if (fast(root)) {
            return true;
        }
        for (int side = 0; side < 2; side++) {
            final int[] cycle = side == 0 ? root.current : root.target;
            for (int i = 0; i < n - 2; i++) {
                for (int j = i + 1; j < n - 1; j++) {
                    for (int k = j + 1; k < n; k++) {
                        final State first = move(root, side, cycle[i], cycle[j], cycle[k]);
                        final int fixed = Integer.bitCount(first.fixed);
                        for (int secondSide = 0; secondSide < 2; secondSide++) {
                            final int[] order = secondSide == 0 ? first.current : first.target;
                            for (int a = 0; a < n - 2; a++) {
                                for (int b = a + 1; b < n - 1; b++) {
                                    for (int c = b + 1; c < n; c++) {
                                        final int u = order[a], v = order[b], w = order[c];
                                        if ((first.fixed & ((1 << u) | (1 << v) | (1 << w))) != 0
                                                || gain(first, secondSide, u, v, w) < 3 - fixed) {
                                            continue;
                                        }

                                        final State second = move(first, secondSide, u, v, w);
                                        if (Integer.bitCount(second.fixed) >= 4 || fast(second)) {
                                            return true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private static boolean fast(final State state) {
        final int n = state.current.length;
        for (int side = 0; side < 2; side++) {
            final int[] order = side == 0 ? state.current : state.target;
            for (int a = 0; a < n - 2; a++) {
                for (int b = a + 1; b < n - 1; b++) {
                    for (int c = b + 1; c < n; c++) {
                        final int u = order[a], v = order[b], w = order[c];
                        if ((state.fixed & ((1 << u) | (1 << v) | (1 << w))) == 0 && gain(state, side, u, v, w) >= 2) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private static int gain(final State state, final int side, final int a, final int b, final int c) {
        final int[] previous = side == 0 ? state.currentPrevious : state.targetPrevious;
        final int[] other = side == 0 ? state.targetNext : state.currentNext;

        return (other[previous[a]] == b ? 1 : 0) + (other[previous[b]] == c ? 1 : 0) + (other[previous[c]] == a ? 1 : 0) - (other[previous[a]] == a ? 1 : 0) - (other[previous[b]] == b ? 1 : 0) - (other[previous[c]] == c ? 1 : 0);
    }

    private static State move(final State state, final int side, final int a, final int b, final int c) {
        final int[] next = (side == 0 ? state.currentNext : state.targetNext).clone();
        final int[] previous = side == 0 ? state.currentPrevious : state.targetPrevious;
        next[previous[a]] = b;
        next[previous[b]] = c;
        next[previous[c]] = a;

        val cycle = new int[next.length];
        int x = 0;
        for (int i = 0; i < cycle.length; i++) {
            cycle[i] = x;
            x = next[x];
        }

        return side == 0 ? state(cycle, state.target) : state(state.current, cycle);
    }

    private static State state(final int[] current, final int[] target) {
        final int n = current.length;
        final int[] cn = new int[n], tn = new int[n], cp = new int[n], tp = new int[n];
        for (int i = 0; i < n; i++) {
            cn[current[i]] = current[(i + 1) % n];
            cp[current[(i + 1) % n]] = current[i];
            tn[target[i]] = target[(i + 1) % n];
            tp[target[(i + 1) % n]] = target[i];
        }

        int mask = 0;
        for (int i = 0; i < n; i++) {
            if (cn[i] == tn[i]) {
                mask |= 1 << cn[i];
            }
        }

        return new State(current, target, cn, tn, cp, tp, mask);
    }
}
