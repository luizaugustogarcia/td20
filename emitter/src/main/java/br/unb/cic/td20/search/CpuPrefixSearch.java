package br.unb.cic.td20.search;

import br.unb.cic.td20.certificate.PrimitiveCertificate;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.permutation.Cycle;
import lombok.val;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * A bounded sorting-prefix heuristic before the full GPU search. Every result
 * reaches identity or closes the actual contracted remainder with a known
 * diameter. A miss leaves the complete original GPU obligation unchanged.
 */
public final class CpuPrefixSearch {

    static final int DEFAULT_DEPTH = 3;
    private static final int DEFAULT_TRIPLE_LIMIT = 8192;
    private static final long DEFAULT_TIMEOUT_MILLIS = 10;
    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private CpuPrefixSearch() {
    }

    /**
     * Production entry: initialize the reusable search workspace without cycle objects.
     */
    public static Optional<PrimitiveCertificate> search(final PrimitivePair pair, final int maximumMoves) {
        return recordedPrimitiveSearch(pair,
                maximumMoves,
                DEFAULT_DEPTH,
                Math.multiplyExact(timeoutMillis(), 1_000_000L));
    }

    static Optional<PrimitiveCertificate> searchWithinNanos(final PrimitivePair pair,
            final int maximumMoves,
            final int defaultDepth,
            final long maximumNanos) {
        if (maximumNanos < 0) {
            throw new IllegalArgumentException("CPU prefix time limit cannot be negative");
        }

        return recordedPrimitiveSearch(pair, maximumMoves, defaultDepth, maximumNanos);
    }

    private static Optional<PrimitiveCertificate> recordedPrimitiveSearch(final PrimitivePair pair,
            final int maximumMoves,
            final int defaultDepth,
            final long maximumNanos) {
        final long started = System.nanoTime();

        try {
            val result = search(pair,
                    maximumMoves,
                    Integer.getInteger("td20.cpuPrefixDepth", defaultDepth),
                    Integer.getInteger("td20.cpuPrefixMaxTriples", DEFAULT_TRIPLE_LIMIT),
                    deadline(started, maximumNanos));
            CpuPrefixSearchStats.instance().completedPrimitive(result, System.nanoTime() - started);

            return result;
        } catch (final RuntimeException | Error error) {
            CpuPrefixSearchStats.instance().failed(System.nanoTime() - started);
            throw error;
        }
    }

    public static Optional<PrimitiveCertificate> search(final PrimitivePair pair,
            final int maximumMoves,
            final int maximumDepth,
            final int maximumTriples) {
        return search(pair, maximumMoves, maximumDepth, maximumTriples, Long.MAX_VALUE);
    }

    private static Optional<PrimitiveCertificate> search(final PrimitivePair pair,
            final int maximumMoves,
            final int maximumDepth,
            final int maximumTriples,
            final long deadlineNanos) {
        Objects.requireNonNull(pair);
        requireLimits(pair.size(), maximumMoves, maximumDepth, maximumTriples);

        val work = WORKSPACE.get();
        initialize(work, pair.size(), maximumMoves, maximumDepth, maximumTriples, deadlineNanos);
        pair.copyOmega(work.omega);
        pair.copyBeta(work.beta[0]);
        work.visit(0, Integer.bitCount(pair.fixedMask()), work.oddCycles());

        return Optional.ofNullable(work.result);
    }

    public static Optional<SortingCertificate> search(final CyclicTargetPair pair, final int maximumMoves) {
        return recordedSearch(pair, maximumMoves, DEFAULT_DEPTH, Math.multiplyExact(timeoutMillis(), 1_000_000L));
    }

    /**
     * General wall-clock limit for roots, endpoints, and reconstructed classes.
     */
    public static long timeoutMillis() {
        final long value = Long.getLong("td20.cpuPrefixMillis", DEFAULT_TIMEOUT_MILLIS);
        if (value < 0) {
            throw new IllegalArgumentException("td20.cpuPrefixMillis must not be negative");
        }

        return value;
    }

    static Optional<SortingCertificate> searchWithinNanos(final CyclicTargetPair pair,
            final int maximumMoves,
            final int defaultDepth,
            final long maximumNanos) {
        if (maximumNanos < 0) {
            throw new IllegalArgumentException("CPU prefix time limit cannot be negative");
        }

        return recordedSearch(pair, maximumMoves, defaultDepth, maximumNanos);
    }

    private static Optional<SortingCertificate> recordedSearch(final CyclicTargetPair pair,
            final int maximumMoves,
            final int defaultDepth,
            final long maximumNanos) {
        final long started = System.nanoTime();

        try {
            val result = search(pair,
                    maximumMoves,
                    Integer.getInteger("td20.cpuPrefixDepth", defaultDepth),
                    Integer.getInteger("td20.cpuPrefixMaxTriples", DEFAULT_TRIPLE_LIMIT),
                    deadline(started, maximumNanos));
            CpuPrefixSearchStats.instance().completed(result, System.nanoTime() - started);

            return result;
        } catch (final RuntimeException | Error error) {
            CpuPrefixSearchStats.instance().failed(System.nanoTime() - started);
            throw error;
        }
    }

    public static Optional<SortingCertificate> search(final CyclicTargetPair pair,
            final int maximumMoves,
            final int maximumDepth,
            final int maximumTriples) {
        return search(pair, maximumMoves, maximumDepth, maximumTriples, Long.MAX_VALUE);
    }

    private static Optional<SortingCertificate> search(final CyclicTargetPair pair,
            final int maximumMoves,
            final int maximumDepth,
            final int maximumTriples,
            final long deadlineNanos) {
        Objects.requireNonNull(pair);
        requireLimits(pair.getBeta().size(), maximumMoves, maximumDepth, maximumTriples);

        val work = WORKSPACE.get();
        initialize(work, pair.getBeta().size(), maximumMoves, maximumDepth, maximumTriples, deadlineNanos);

        int fixed = 0;
        for (int symbol = 0; symbol < work.n; symbol++) {
            work.omega[symbol] = pair.getOmega().image(symbol);
            work.beta[0][symbol] = pair.getBeta().get(symbol);
            if (work.omega[symbol] == symbol) {
                fixed++;
            }
        }

        work.visit(0, fixed, work.oddCycles());

        return Optional.ofNullable(work.result).map(CpuPrefixSearch::legacy);
    }

    private static void requireLimits(final int domain, final int maximumMoves, final int maximumDepth, final int maximumTriples) {
        if (maximumMoves < 1
                || maximumMoves > KnownDiameters.MAX_BUDGET
                || maximumDepth < 0
                || maximumDepth > KnownDiameters.MAX_BUDGET
                || maximumTriples < 0
                || domain < 1
                || domain > KnownDiameters.ROOT_SYMBOLS) {
            throw new IllegalArgumentException("Invalid CPU prefix domain, budget, depth, or triple limit");
        }
    }

    private static void initialize(final Workspace work,
            final int domain,
            final int maximumMoves,
            final int maximumDepth,
            final int maximumTriples,
            final long deadlineNanos) {
        work.n = domain;
        work.maximumMoves = maximumMoves;
        work.maximumDepth = Math.min(maximumDepth, maximumMoves);
        work.remainingTriples = maximumTriples;
        work.deadlineNanos = deadlineNanos;
        work.result = null;
    }

    /**
     * Compatibility boundary for mathematical tests and the previous public API.
     */
    private static SortingCertificate legacy(final PrimitiveCertificate result) {
        val moves = new ArrayList<Cycle>(result.moveCount());
        for (int step = 0; step < result.moveCount(); step++) {
            final int move = result.moveAt(step);
            moves.add(Cycle.of(move & 31, (move >>> 5) & 31, (move >>> 10) & 31));
        }

        return result.kind() == PrimitiveCertificate.Kind.COMPLETE ? SortingCertificate.complete(moves) : SortingCertificate.closure(moves);
    }

    private static long deadline(final long started, final long maximumNanos) {
        if (maximumNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }

        try {
            return Math.addExact(started, maximumNanos);
        } catch (final ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static final class Workspace {

        final int[] omega = new int[KnownDiameters.ROOT_SYMBOLS];
        final int[][] beta = new int[KnownDiameters.MAX_BUDGET + 1][KnownDiameters.ROOT_SYMBOLS];
        final int[][] positions = new int[KnownDiameters.MAX_BUDGET][KnownDiameters.ROOT_SYMBOLS];
        final int[] stack = new int[KnownDiameters.MAX_BUDGET];
        int n, maximumMoves, maximumDepth, remainingTriples;
        long deadlineNanos;
        PrimitiveCertificate result;

        boolean visit(final int depth, final int fixed, final int odd) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("CPU search interrupted");
            }
            if (fixed == n || KnownDiameters.knownDiameter(n - fixed) <= maximumMoves - depth) {
                final int[] moves = Arrays.copyOf(stack, depth);
                result = fixed == n ? PrimitiveCertificate.complete(moves) : PrimitiveCertificate.closure(moves);

                return true;
            }
            if (deadlineReached()) {
                return false;
            }
            if (depth == maximumDepth || remainingTriples == 0) {
                return false;
            }
            // A permitted move increases the odd-length-cycle count by at
            // most two. A prefix cannot close a budget below this lower bound.
            if ((n - odd) / 2 > maximumMoves - depth) {
                return false;
            }
            // At most three new fixed points can be created by one move. This
            // only limits the heuristic; it does not discharge an obligation.
            if (!canClose(depth, fixed)) {
                return false;
            }
            if (visitConsecutiveSupports(depth, fixed, odd)) {
                return true;
            }
            if (canClose(depth + 1, fixed + 1) && visitGainOneSupports(depth, fixed, odd)) {
                return true;
            }
            if (!canClose(depth + 1, fixed)) {
                return false;
            }

            // Only gain-zero moves remain. Follow each as it is encountered;
            // collecting a full cubic candidate list first wastes the shared
            // support budget before any child can use it.
            final int[] order = beta[depth];
            for (int i = 0; i < n - 2; i++) {
                final int a = order[i];
                if (omega[a] == a) {
                    continue;
                }
                for (int j = i + 1; j < n - 1; j++) {
                    final int b = order[j];
                    if (omega[b] == b) {
                        continue;
                    }
                    for (int k = j + 1; k < n; k++) {
                        if (deadlineReached()) {
                            return false;
                        }

                        final int c = order[k];
                        if (omega[c] == c) {
                            continue;
                        }
                        if (remainingTriples == 0) {
                            return false;
                        }

                        remainingTriples--;

                        final int gain = (omega[c] == a ? 1 : 0) + (omega[a] == b ? 1 : 0) + (omega[b] == c ? 1 : 0);
                        if (gain == 0 && visitMove(depth, i, j, k, fixed, odd)) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }

        /**
         * Apply the paper's consecutive-triple lemma after virtually contracting
         * fixed symbols: deleting them preserves the order on each nonfixed
         * support. Every support creating at least two new fixed points then
         * has two consecutive omega edges after a cyclic rotation.
         * Follow these at most N supports before scanning general triples,
         * including when more prefix moves are needed to reach a diameter
         * exit. This is ordering only; a miss still reaches the general prefix
         * search and the original full GPU obligation.
         */
        boolean visitConsecutiveSupports(final int depth, final int fixed, final int odd) {
            final int[] order = beta[depth], position = positions[depth];
            for (int index = 0; index < n; index++) {
                position[order[index]] = index;
            }
            // Prefer clearing an entire 3-cycle to gaining two fixed points.
            for (int wantedGain = 3; wantedGain >= 2; wantedGain--) {
                for (int symbol = 0; symbol < n && remainingTriples > 0; symbol++) {
                    if (deadlineReached()) {
                        return false;
                    }

                    final int a = symbol, b = omega[a], c = omega[b];
                    if (a == b || b == c || c == a || omega[c] == c) {
                        continue;
                    }
                    // The three origins of an algebraic 3-cycle give one move.
                    if (omega[c] == a && (a > b || a > c)) {
                        continue;
                    }

                    final int gain = 2 + (omega[c] == a ? 1 : 0);
                    if (gain != wantedGain) {
                        continue;
                    }
                    if (!canClose(depth + 1, fixed + gain)) {
                        continue;
                    }

                    final int pa = position[a], pb = position[b], pc = position[c];
                    if (!((pa < pb && pb < pc) || (pb < pc && pc < pa) || (pc < pa && pa < pb))) {
                        continue;
                    }

                    remainingTriples--;

                    final int i = Math.min(pa, Math.min(pb, pc)), k = Math.max(pa, Math.max(pb, pc));
                    final int j = pa + pb + pc - i - k;
                    if (visitMove(depth, i, j, k, fixed + gain, odd)) {
                        return true;
                    }
                }
            }

            return false;
        }

        /**
         * A gain-one move has exactly one edge agreeing with omega, so its
         * cyclic support is uniquely (a, omega(a), c). Inspect O(N^2) supports
         * rather than first building all binomial(N, 3) triples. This includes
         * supports within even-length cycles and supports across cycles.
         */
        boolean visitGainOneSupports(final int depth, final int fixed, final int odd) {
            final int[] position = positions[depth];
            for (int a = 0; a < n; a++) {
                final int b = omega[a];
                if (a == b) {
                    continue;
                }
                for (int c = 0; c < n; c++) {
                    if (deadlineReached()) {
                        return false;
                    }
                    if (c == a || c == b || omega[c] == c) {
                        continue;
                    }
                    if (remainingTriples == 0) {
                        return false;
                    }

                    remainingTriples--;
                    // already tried gain >= 2
                    if (omega[b] == c || omega[c] == a) {
                        continue;
                    }

                    final int pa = position[a], pb = position[b], pc = position[c];
                    if (!((pa < pb && pb < pc) || (pb < pc && pc < pa) || (pc < pa && pa < pb))) {
                        continue;
                    }

                    final int i = Math.min(pa, Math.min(pb, pc)), k = Math.max(pa, Math.max(pb, pc));
                    final int j = pa + pb + pc - i - k;
                    if (visitMove(depth, i, j, k, fixed + 1, odd)) {
                        return true;
                    }
                }
            }

            return false;
        }

        boolean visitMove(final int depth, final int i, final int j, final int k, final int fixed, final int odd) {
            final int[] order = beta[depth];
            final int a = order[i], b = order[j], c = order[k];
            final int oa = omega[a], ob = omega[b], oc = omega[c];
            omega[a] = oc;
            omega[b] = oa;
            omega[c] = ob;

            final int childOdd = oddCycles();
            boolean found = false;
            if (childOdd - odd == 0 || childOdd - odd == 2) {
                stack[depth] = a | (b << 5) | (c << 10);

                final int[] nextOrder = beta[depth + 1];
                System.arraycopy(order, 0, nextOrder, 0, n);
                System.arraycopy(order, j, nextOrder, i, k - j);
                System.arraycopy(order, i, nextOrder, i + k - j, j - i);
                found = visit(depth + 1, fixed, childOdd);
            }

            omega[a] = oa;
            omega[b] = ob;
            omega[c] = oc;

            return found;
        }

        boolean canClose(final int depth, final int fixed) {
            for (int used = depth; used <= maximumDepth; used++) {
                final int remainder = Math.max(0, n - fixed - 3 * (used - depth));
                if (KnownDiameters.knownDiameter(remainder) <= maximumMoves - used) {
                    return true;
                }
            }

            return false;
        }

        boolean deadlineReached() {
            return deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0;
        }

        int oddCycles() {
            int seen = 0, odd = 0;
            for (int symbol = 0; symbol < n; symbol++) {
                if ((seen & (1 << symbol)) != 0) {
                    continue;
                }

                int current = symbol, length = 0;
                do {
                    seen |= 1 << current;
                    length++;
                    current = omega[current];
                } while (current != symbol);

                odd += length & 1;
            }

            return odd;
        }
    }
}
