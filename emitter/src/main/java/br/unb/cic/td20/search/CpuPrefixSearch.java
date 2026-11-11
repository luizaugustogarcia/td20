package br.unb.cic.td20.search;

import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * A bounded sorting-prefix heuristic before the full GPU search. Every result
 * reaches identity or closes the actual contracted remainder with a known
 * diameter. A miss leaves the complete original GPU obligation unchanged.
 */
public final class CpuPrefixSearch {
    private static final int DEFAULT_DEPTH = 3;
    private static final int DEFAULT_TRIPLE_LIMIT = 8192;
    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private CpuPrefixSearch() { }

    public static Optional<SortingCertificate> search(CyclicTargetPair pair, int maximumMoves) {
        return recordedSearch(pair, maximumMoves, Long.MAX_VALUE);
    }

    static Optional<SortingCertificate> searchWithinNanos(CyclicTargetPair pair, int maximumMoves,
                                                          long maximumNanos) {
        if (maximumNanos < 0) throw new IllegalArgumentException("CPU prefix time limit cannot be negative");
        return recordedSearch(pair, maximumMoves, maximumNanos);
    }

    private static Optional<SortingCertificate> recordedSearch(CyclicTargetPair pair, int maximumMoves,
                                                               long maximumNanos) {
        long started = System.nanoTime();
        try {
            var result = search(pair, maximumMoves,
                    Integer.getInteger("td20.cpuPrefixDepth", DEFAULT_DEPTH),
                    Integer.getInteger("td20.cpuPrefixMaxTriples", DEFAULT_TRIPLE_LIMIT),
                    deadline(started, maximumNanos));
            CpuPrefixSearchStats.instance().completed(result, System.nanoTime() - started);
            return result;
        } catch (RuntimeException | Error error) {
            CpuPrefixSearchStats.instance().failed(System.nanoTime() - started);
            throw error;
        }
    }

    public static Optional<SortingCertificate> search(CyclicTargetPair pair, int maximumMoves,
                                                int maximumDepth, int maximumTriples) {
        return search(pair, maximumMoves, maximumDepth, maximumTriples, Long.MAX_VALUE);
    }

    private static Optional<SortingCertificate> search(CyclicTargetPair pair, int maximumMoves,
                                                       int maximumDepth, int maximumTriples,
                                                       long deadlineNanos) {
        Objects.requireNonNull(pair);
        if (maximumMoves < 1 || maximumMoves > KnownDiameters.MAX_BUDGET
                || maximumDepth < 0 || maximumDepth > KnownDiameters.MAX_BUDGET
                || maximumTriples < 0 || pair.getBeta().size() > KnownDiameters.ROOT_SYMBOLS)
            throw new IllegalArgumentException("Invalid CPU prefix domain, budget, depth, or triple limit");
        var work = WORKSPACE.get();
        work.n = pair.getBeta().size();
        work.maximumMoves = maximumMoves;
        work.maximumDepth = Math.min(maximumDepth, maximumMoves);
        work.remainingTriples = maximumTriples;
        work.deadlineNanos = deadlineNanos;
        work.result = null;
        int fixed = 0;
        for (int symbol = 0; symbol < work.n; symbol++) {
            work.omega[symbol] = pair.getOmega().image(symbol);
            work.beta[0][symbol] = pair.getBeta().get(symbol);
            if (work.omega[symbol] == symbol) fixed++;
        }
        work.visit(0, fixed, work.oddCycles());
        return Optional.ofNullable(work.result);
    }

    private static long deadline(long started, long maximumNanos) {
        if (maximumNanos == Long.MAX_VALUE) return Long.MAX_VALUE;
        try { return Math.addExact(started, maximumNanos); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    private static final class Workspace {
        final int[] omega = new int[KnownDiameters.ROOT_SYMBOLS];
        final int[][] beta = new int[KnownDiameters.MAX_BUDGET + 1][KnownDiameters.ROOT_SYMBOLS];
        final int[][] positions = new int[KnownDiameters.MAX_BUDGET][KnownDiameters.ROOT_SYMBOLS];
        final int[] stack = new int[KnownDiameters.MAX_BUDGET];
        int n, maximumMoves, maximumDepth, remainingTriples;
        long deadlineNanos;
        SortingCertificate result;

        boolean visit(int depth, int fixed, int odd) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("CPU search interrupted");
            if (fixed == n || KnownDiameters.knownDiameter(n - fixed) <= maximumMoves - depth) {
                var moves = new ArrayList<Cycle>(depth);
                for (int step = 0; step < depth; step++) {
                    int move = stack[step];
                    moves.add(Cycle.of(move & 31, (move >>> 5) & 31, move >>> 10));
                }
                result = fixed == n ? SortingCertificate.complete(moves) : SortingCertificate.closure(moves);
                return true;
            }
            if (deadlineReached()) return false;
            if (depth == maximumDepth || remainingTriples == 0) return false;
            // A permitted move increases the odd-length-cycle count by at
            // most two. A prefix cannot close a budget below this lower bound.
            if ((n - odd) / 2 > maximumMoves - depth) return false;
            // At most three new fixed points can be created by one move. This
            // only limits the heuristic; it does not discharge an obligation.
            if (!canClose(depth, fixed)) return false;
            if (visitConsecutiveSupports(depth, fixed, odd)) return true;
            if (canClose(depth + 1, fixed + 1) && visitGainOneSupports(depth, fixed, odd)) return true;
            if (!canClose(depth + 1, fixed)) return false;
            // Only gain-zero moves remain. Follow each as it is encountered;
            // collecting a full cubic candidate list first wastes the shared
            // support budget before any child can use it.
            int[] order = beta[depth];
            for (int i = 0; i < n - 2; i++) {
                int a = order[i];
                if (omega[a] == a) continue;
                for (int j = i + 1; j < n - 1; j++) {
                    int b = order[j];
                    if (omega[b] == b) continue;
                    for (int k = j + 1; k < n; k++) {
                        if (deadlineReached()) return false;
                        int c = order[k];
                        if (omega[c] == c) continue;
                        if (remainingTriples == 0) return false;
                        remainingTriples--;
                        int gain = (omega[c] == a ? 1 : 0) + (omega[a] == b ? 1 : 0)
                                + (omega[b] == c ? 1 : 0);
                        if (gain == 0 && visitMove(depth, i, j, k, fixed, odd)) return true;
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
        boolean visitConsecutiveSupports(int depth, int fixed, int odd) {
            int[] order = beta[depth], position = positions[depth];
            for (int index = 0; index < n; index++) position[order[index]] = index;
            // Prefer clearing an entire 3-cycle to gaining two fixed points.
            for (int wantedGain = 3; wantedGain >= 2; wantedGain--)
            for (int symbol = 0; symbol < n && remainingTriples > 0; symbol++) {
                if (deadlineReached()) return false;
                int a = symbol, b = omega[a], c = omega[b];
                if (a == b || b == c || c == a || omega[c] == c) continue;
                // The three origins of an algebraic 3-cycle give one move.
                if (omega[c] == a && (a > b || a > c)) continue;
                int gain = 2 + (omega[c] == a ? 1 : 0);
                if (gain != wantedGain) continue;
                if (!canClose(depth + 1, fixed + gain)) continue;
                int pa = position[a], pb = position[b], pc = position[c];
                if (!((pa < pb && pb < pc) || (pb < pc && pc < pa) || (pc < pa && pa < pb))) continue;
                remainingTriples--;
                int i = Math.min(pa, Math.min(pb, pc)), k = Math.max(pa, Math.max(pb, pc));
                int j = pa + pb + pc - i - k;
                if (visitMove(depth, i, j, k, fixed + gain, odd)) return true;
            }
            return false;
        }

        /**
         * A gain-one move has exactly one edge agreeing with omega, so its
         * cyclic support is uniquely (a, omega(a), c). Inspect O(N^2) supports
         * rather than first building all binomial(N, 3) triples. This includes
         * supports within even-length cycles and supports across cycles.
         */
        boolean visitGainOneSupports(int depth, int fixed, int odd) {
            int[] position = positions[depth];
            for (int a = 0; a < n; a++) {
                int b = omega[a];
                if (a == b) continue;
                for (int c = 0; c < n; c++) {
                    if (deadlineReached()) return false;
                    if (c == a || c == b || omega[c] == c) continue;
                    if (remainingTriples == 0) return false;
                    remainingTriples--;
                    if (omega[b] == c || omega[c] == a) continue; // already tried gain >= 2
                    int pa = position[a], pb = position[b], pc = position[c];
                    if (!((pa < pb && pb < pc) || (pb < pc && pc < pa) || (pc < pa && pa < pb))) continue;
                    int i = Math.min(pa, Math.min(pb, pc)), k = Math.max(pa, Math.max(pb, pc));
                    int j = pa + pb + pc - i - k;
                    if (visitMove(depth, i, j, k, fixed + 1, odd)) return true;
                }
            }
            return false;
        }

        boolean visitMove(int depth, int i, int j, int k, int fixed, int odd) {
            int[] order = beta[depth];
            int a = order[i], b = order[j], c = order[k];
            int oa = omega[a], ob = omega[b], oc = omega[c];
            omega[a] = oc; omega[b] = oa; omega[c] = ob;
            int childOdd = oddCycles();
            boolean found = false;
            if (childOdd - odd == 0 || childOdd - odd == 2) {
                stack[depth] = a | (b << 5) | (c << 10);
                int[] nextOrder = beta[depth + 1];
                System.arraycopy(order, 0, nextOrder, 0, n);
                System.arraycopy(order, j, nextOrder, i, k - j);
                System.arraycopy(order, i, nextOrder, i + k - j, j - i);
                found = visit(depth + 1, fixed, childOdd);
            }
            omega[a] = oa; omega[b] = ob; omega[c] = oc;
            return found;
        }

        boolean canClose(int depth, int fixed) {
            for (int used = depth; used <= maximumDepth; used++) {
                int remainder = Math.max(0, n - fixed - 3 * (used - depth));
                if (KnownDiameters.knownDiameter(remainder) <= maximumMoves - used) return true;
            }
            return false;
        }

        boolean deadlineReached() {
            return deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0;
        }

        int oddCycles() {
            int seen = 0, odd = 0;
            for (int symbol = 0; symbol < n; symbol++) {
                if ((seen & (1 << symbol)) != 0) continue;
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
