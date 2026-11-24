package br.unb.cic.td20.joint;

import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.joint.PrimitiveInverseReconstruction.RawConsumer;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Checker-owned final inverse cover, with independently checked elementary
 * families and explicit residual parents. This implementation uses path/cycle
 * vertex-cover counts and direct block exchange, independently of the emitter.
 * Each workspace belongs to one worker; output pairs own their data.
 */
public final class FinalInverseCover {

    private static final int MAX = ErikssonRoute.ROOT_SYMBOLS;
    private final int[] base = new int[MAX], baseInverse = new int[MAX];
    private final int[] current = new int[MAX], target = new int[MAX];
    private final int[] original = new int[MAX], opposite = new int[MAX];
    private final int[] graph = new int[MAX], oppositePositions = new int[MAX];
    private final int[] restoredOrder = new int[MAX], restoredNext = new int[MAX];
    private final int[] restoredPositions = new int[MAX], targetNext = new int[MAX];
    private final int[] targetOrder = new int[MAX], targetPositions = new int[MAX];
    private final int[] parent = new int[MAX], parentInverse = new int[MAX];
    private final int[] parentOrder = new int[MAX], parentNext = new int[MAX], parentPositions = new int[MAX];
    private final int[] support = new int[3];
    private boolean busy, failed;
    private long sources, sourceSides, closedSides, supportChoices, closedSupports;
    private long candidateSlots, closedCandidateSlots, invalidCandidates, replayedParents, immediateClosed, residualParents;

    /**
     * Slots include inadmissible parents; they are never distinct-root counts.
     */
    public record Counts(long sources, long sourceSides, long closedSides, long supportChoices, long closedSupports, long candidateSlots, long closedCandidateSlots, long invalidCandidates, long replayedParents, long immediateClosed, long residualParents) {

        public Counts {
            if (sources < 0
                    || sourceSides != 2 * sources
                    || closedSides < 0
                    || closedSides > sourceSides
                    || supportChoices < 0
                    || closedSupports < 0
                    || closedSupports > supportChoices
                    || candidateSlots < 0
                    || closedCandidateSlots < 0
                    || closedCandidateSlots > candidateSlots
                    || invalidCandidates < 0
                    || replayedParents < 0
                    || immediateClosed < 0
                    || residualParents < 0
                    || Math.addExact(invalidCandidates, replayedParents) != candidateSlots - closedCandidateSlots
                    || Math.addExact(immediateClosed, residualParents) != replayedParents) {
                throw new IllegalArgumentException("Incomplete final inverse cover accounting");
            }
        }

        public Counts plus(final Counts other) {
            return new Counts(Math.addExact(sources, other.sources),
                    Math.addExact(sourceSides, other.sourceSides),
                    Math.addExact(closedSides, other.closedSides),
                    Math.addExact(supportChoices, other.supportChoices),
                    Math.addExact(closedSupports, other.closedSupports),
                    Math.addExact(candidateSlots, other.candidateSlots),
                    Math.addExact(closedCandidateSlots, other.closedCandidateSlots),
                    Math.addExact(invalidCandidates, other.invalidCandidates),
                    Math.addExact(replayedParents, other.replayedParents),
                    Math.addExact(immediateClosed, other.immediateClosed),
                    Math.addExact(residualParents, other.residualParents));
        }

        public static Counts empty() {
            return new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public Counts counts() {
        if (busy || failed) {
            throw new IllegalStateException("Final inverse cover is incomplete");
        }

        return new Counts(sources,
                sourceSides,
                closedSides,
                supportChoices,
                closedSupports,
                candidateSlots,
                closedCandidateSlots,
                invalidCandidates,
                replayedParents,
                immediateClosed,
                residualParents);
    }

    /**
     * Covers both endpoints, every old support and every restored-symbol gap.
     * Omitted parents have an analytic gain-at-least-two closure within eleven;
     * every emitted parent still requires a database witness at that budget.
     * Domains below 21 support exhaustive oracle tests of the same predicate.
     */
    public void forEachResidual(final CyclicTargetPair child, final int gain, final Consumer<CyclicTargetPair> output) {
        Objects.requireNonNull(output);
        forEachResidual(LegacyPairs.fromLegacy(child),
                gain,
                (omega, beta, n) -> output.accept(LegacyPairs.toLegacy(PrimitivePair.of(Arrays.copyOf(omega, n), Arrays.copyOf(beta, n)))));
    }

    /**
     * Borrowed residual arrays are packed by the persistence callback before workspace reuse.
     */
    public void forEachResidual(final PrimitivePair child, final int gain, final RawConsumer output) {
        Objects.requireNonNull(child);
        Objects.requireNonNull(output);
        if (busy || failed) {
            throw new IllegalStateException("Final inverse workspace is busy or failed");
        }

        final int m = child.size(), n = m + gain;
        if (m < 3 || gain < 0 || gain > 1 || n > MAX) {
            throw new IllegalArgumentException("Final inverse cover requires gain zero/one and at most 21 symbols");
        }
        if (KnownDiameters.knownDiameter(n - 2) > ErikssonRoute.ROOT_BUDGET - 1) {
            throw new IllegalStateException("The family closure exceeds the actual root budget");
        }

        loadAndCheck(child, m);
        busy = true;

        try {
            coverEndpoint(m, n, gain, false, output);
            coverEndpoint(m, n, gain, true, output);
            sources++;
        } catch (final RuntimeException | Error error) {
            failed = true;
            throw error;
        } finally {
            busy = false;
        }
    }

    private void loadAndCheck(final PrimitivePair child, final int m) {
        int images = 0, betaSymbols = 0, rhoSymbols = 0;
        for (int i = 0; i < m; i++) {
            final int image = child.omegaImage(i), b = child.betaAt(i), r = child.rhoAt(i);
            if (image < 0
                    || image >= m
                    || b < 0
                    || b >= m
                    || r < 0
                    || r >= m
                    || (images & (1 << image)) != 0
                    || (betaSymbols & (1 << b)) != 0
                    || (rhoSymbols & (1 << r)) != 0) {
                throw new IllegalArgumentException("Inverse source must be a valid pair on the complete normalized domain");
            }
            if (image == i) {
                throw new IllegalArgumentException("Contract fixed symbols before inverse coverage");
            }

            images |= 1 << image;
            betaSymbols |= 1 << b;
            rhoSymbols |= 1 << r;
            base[i] = image;
            baseInverse[image] = i;
            current[i] = b;
            target[i] = r;
        }
        for (int i = 0; i < m; i++) {
            targetNext[target[i]] = target[(i + 1) % m];
        }
        for (int i = 0; i < m; i++) {
            if (base[current[(i + 1) % m]] != targetNext[current[i]]) {
                throw new IllegalArgumentException("Inverse source target does not equal omega * beta");
            }
        }
    }

    private void coverEndpoint(final int m, final int n, final int gain, final boolean exchanged, final RawConsumer output) {
        sourceSides++;

        final int oldCount = 3 - gain, gaps = gain == 0 ? 1 : m;
        final int choices = oldCount == 2 ? m * (m - 1) / 2 : m * (m - 1) * (m - 2) / 6;
        supportChoices += choices;
        candidateSlots += (long) choices * gaps;

        final int[] order = exchanged ? target : current;
        final int[] oppositeOrder = exchanged ? current : target;
        System.arraycopy(exchanged ? baseInverse : base, 0, original, 0, m);
        System.arraycopy(exchanged ? base : baseInverse, 0, opposite, 0, m);
        for (int i = 0; i < m; i++) {
            oppositePositions[oppositeOrder[i]] = i;
        }
        if (gain == 1) {
            original[m] = m;
        }

        Arrays.fill(graph, 0);
        for (int x = 0; x < m; x++) {
            final int y = opposite[x], z = opposite[y];
            if (x == y || y == z || z == x || !cyclic(oppositePositions[x], oppositePositions[y], oppositePositions[z])) {
                continue;
            }

            graph[y] |= 1 << z;
            graph[z] |= 1 << y;
        }
        if (minimumCover(m) > oldCount) {
            closedSides++;
            closedSupports += choices;
            closedCandidateSlots += (long) choices * gaps;

            return;
        }

        // Iterate fixed-cardinality subsets by their bit pattern. Unlike the
        // emitter's cover recursion this independently visits every old support.
        int mask = (1 << oldCount) - 1, limit = 1 << m;
        while (mask < limit) {
            if (hasUntouchedEdge(mask, m)) {
                closedSupports++;
                closedCandidateSlots += gaps;
            } else {
                int bits = mask, cursor = 0;
                while (bits != 0) {
                    final int bit = Integer.lowestOneBit(bits);
                    support[cursor++] = Integer.numberOfTrailingZeros(bit);
                    bits ^= bit;
                }
                if (gain == 1) {
                    support[2] = m;
                }
                for (int gap = 0; gap < gaps; gap++) {
                    consider(order, m, n, gain, gain == 0 ? -1 : gap, exchanged, output);
                }
            }

            final int low = mask & -mask, ripple = mask + low;
            mask = ripple | (((mask ^ ripple) >>> 2) / low);
        }
    }

    /**
     * The graph is independently checked to be a union of paths and cycles.
     */
    private int minimumCover(final int m) {
        int visited = 0, cover = 0;
        for (int start = 0; start < m; start++) {
            if (graph[start] == 0 || (visited & (1 << start)) != 0) {
                continue;
            }

            int frontier = 1 << start, vertices = 0, endpoints = 0;
            while (frontier != 0) {
                final int bit = Integer.lowestOneBit(frontier);
                frontier ^= bit;
                if ((visited & bit) != 0) {
                    continue;
                }

                visited |= bit;
                vertices++;

                final int node = Integer.numberOfTrailingZeros(bit), degree = Integer.bitCount(graph[node]);
                if (degree < 1 || degree > 2) {
                    throw new IllegalStateException("Inverse witness graph is not a path/cycle union");
                }
                if (degree == 1) {
                    endpoints++;
                }

                frontier |= graph[node] & ~visited;
            }
            if (endpoints != 0 && endpoints != 2) {
                throw new IllegalStateException("Malformed inverse witness component");
            }

            cover += endpoints == 0 ? (vertices + 1) / 2 : vertices / 2;
        }

        return cover;
    }

    private boolean hasUntouchedEdge(final int supportMask, final int m) {
        for (int vertex = 0; vertex < m; vertex++) {
            if ((supportMask & (1 << vertex)) == 0 && (graph[vertex] & ~supportMask) != 0) {
                return true;
            }
        }

        return false;
    }

    private void consider(final int[] sourceOrder,
            final int m,
            final int n,
            final int gain,
            final int gap,
            final boolean exchanged,
            final RawConsumer output) {
        for (int i = 0, cursor = 0; i < m; i++) {
            restoredOrder[cursor++] = sourceOrder[i];
            if (i == gap) {
                restoredOrder[cursor++] = m;
            }
        }

        successors(restoredOrder, n, restoredNext, restoredPositions);

        int i = restoredPositions[support[0]], j = restoredPositions[support[1]], k = restoredPositions[support[2]];
        if (i > j) {
            final int t = i;
            i = j;
            j = t;
        }
        if (j > k) {
            final int t = j;
            j = k;
            k = t;
        }
        if (i > j) {
            final int t = i;
            i = j;
            j = t;
        }

        final int a = restoredOrder[i], b = restoredOrder[j], c = restoredOrder[k];
        System.arraycopy(original, 0, parent, 0, n);
        parent[a] = original[c];
        parent[b] = original[a];
        parent[c] = original[b];
        for (int symbol = 0; symbol < n; symbol++) {
            if (parent[symbol] == symbol) {
                invalidCandidates++;
                return;
            }
        }

        // Apply the transposition as a direct exchange of the two adjacent
        // blocks. This does not share the emitter's successor-traversal code.
        System.arraycopy(restoredOrder, 0, parentOrder, 0, n);
        System.arraycopy(restoredOrder, j, parentOrder, i, k - j);
        System.arraycopy(restoredOrder, i, parentOrder, i + k - j, j - i);
        successors(parentOrder, n, parentNext, parentPositions);

        int p = a, q = b, r = c;
        if (parentPositions[p] > parentPositions[q]) {
            final int t = p;
            p = q;
            q = t;
        }
        if (parentPositions[q] > parentPositions[r]) {
            final int t = q;
            q = r;
            r = t;
        }
        if (parentPositions[p] > parentPositions[q]) {
            final int t = p;
            p = q;
            q = t;
        }

        int fixed = 0;
        for (int symbol = 0; symbol < n; symbol++) {
            final int replayed = symbol == p ? parent[r] : symbol == q ? parent[p] : symbol == r ? parent[q] : parent[symbol];
            if (replayed == symbol) {
                fixed++;
            }

            final int next = parentNext[symbol];
            final int edge = next == p ? q : next == q ? r : next == r ? p : next;
            if (replayed != original[symbol] || edge != restoredNext[symbol]) {
                throw new IllegalStateException("Independent final inverse failed full forward replay");
            }

            targetNext[symbol] = original[restoredNext[symbol]];
            if (parent[parentNext[symbol]] != targetNext[symbol]) {
                throw new IllegalStateException("Independent final inverse changed its target");
            }

            parentInverse[parent[symbol]] = symbol;
        }
        if (fixed != gain) {
            invalidCandidates++;
            return;
        }

        replayedParents++;
        if (closesOn(parent, parentPositions, n)) {
            immediateClosed++;
            return;
        }

        int symbol = 0, seen = 0;
        for (int at = 0; at < n; at++) {
            if ((seen & (1 << symbol)) != 0) {
                throw new IllegalStateException("Reconstructed target is not a full cycle");
            }

            seen |= 1 << symbol;
            targetOrder[at] = symbol;
            targetPositions[symbol] = at;
            symbol = targetNext[symbol];
        }
        if (symbol != 0) {
            throw new IllegalStateException("Reconstructed target cycle did not close");
        }
        if (closesOn(parentInverse, targetPositions, n)) {
            immediateClosed++;
            return;
        }

        output.accept(exchanged ? parentInverse : parent, exchanged ? targetOrder : parentOrder, n);
        residualParents++;
    }

    /**
     * Replay the changed algebraic images, count fixed symbols, and check the actual remaining diameter budget.
     */
    private static boolean closesOn(final int[] images, final int[] positions, final int n) {
        for (int a = 0; a < n; a++) {
            final int b = images[a], c = images[b];
            if (a == b || b == c || c == a || !cyclic(positions[a], positions[b], positions[c])) {
                continue;
            }

            int fixed = 0;
            for (int symbol = 0; symbol < n; symbol++) {
                final int image = symbol == a ? images[c] : symbol == b ? images[a] : symbol == c ? images[b] : images[symbol];
                if (image == symbol) {
                    fixed++;
                }
            }
            if (fixed < 2) {
                throw new IllegalStateException("Consecutive inverse closure did not create two fixed symbols");
            }
            if (KnownDiameters.knownDiameter(n - fixed) <= ErikssonRoute.ROOT_BUDGET - 1) {
                return true;
            }
        }

        return false;
    }

    private static boolean cyclic(final int a, final int b, final int c) {
        return a < b && b < c || b < c && c < a || c < a && a < b;
    }

    private static void successors(final int[] order, final int n, final int[] next, final int[] positions) {
        for (int i = 0; i < n; i++) {
            positions[order[i]] = i;
            next[order[i]] = order[(i + 1) % n];
        }
    }
}
