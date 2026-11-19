package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.PrimitivePair;

import java.util.Objects;

/**
 * Worker-owned inverse reconstruction on primitive successor tables.
 *
 * <p>Enumerates the same gap placements and supports as the production inverse
 * reconstructor, on both endpoints, retaining exact gain and forward replay.
 * Construction allocates its fixed-size workspace once; enumeration allocates
 * no objects per candidate. The production inverse waves use this implementation.
 * Each instance belongs to one worker and cannot be used concurrently or
 * recursively. Generated states are not deduplicated.
 */
public final class PrimitiveInverseReconstruction {
    private static final int CAPACITY = ErikssonRoute.ROOT_SYMBOLS;

    /**
     * Receives borrowed workspace arrays. Only the first {@code length} entries
     * are valid. A callback must neither mutate nor retain them: copy the needed
     * data or produce an immutable key before returning.
     */
    @FunctionalInterface
    public interface RawConsumer {
        void accept(int[] omega, int[] beta, int length);
    }

    private final int[] baseOmega = new int[CAPACITY];
    private final int[] baseBeta = new int[CAPACITY];
    private final int[] inverseBaseOmega = new int[CAPACITY];
    private final int[] targetBaseBeta = new int[CAPACITY];
    private final int[] originalOmega = new int[CAPACITY];
    private final int[] parentOmega = new int[CAPACITY];
    private final int[] expandedBeta = new int[CAPACITY];
    private final int[] expandedNext = new int[CAPACITY];
    private final int[] expandedPositions = new int[CAPACITY];
    private final int[] parentBeta = new int[CAPACITY];
    private final int[] parentPositions = new int[CAPACITY];
    private final int[] inverseParentOmega = new int[CAPACITY];
    private final int[] expandedTargetBeta = new int[CAPACITY];
    private final int[] slots = new int[3];

    private int sourceLength;
    private int length;
    private int gain;
    private int[] currentSourceBeta;
    private RawConsumer output;
    private boolean swappedOutput;
    private boolean busy;
    private long generated;

    /**
     * Reconstructs fixed-point-free parents by restoring {@code gain} symbols.
     * The returned count includes repeated classes, as does the production
     * generator. Input fixed points must have been contracted first.
     */
    public long preimages(final PrimitivePair child, final int gain, final RawConsumer output) {
        Objects.requireNonNull(child);
        Objects.requireNonNull(output);
        if (busy) throw new IllegalStateException("Primitive reconstruction workspace is already in use");
        final int m = child.size();
        if (gain < 0 || gain > 3 || m + gain > CAPACITY)
            throw new IllegalArgumentException("Invalid inverse gain or domain");
        for (int x = 0; x < m; x++) {
            final int image = child.omegaImage(x);
            if (image == x)
                throw new IllegalArgumentException("Contract fixed points before reconstruction");
            baseOmega[x] = image;
            inverseBaseOmega[image] = x;
            baseBeta[x] = child.betaAt(x);
            targetBaseBeta[x] = child.rhoAt(x);
        }
        busy = true;
        this.sourceLength = m;
        this.length = m + gain;
        this.gain = gain;
        this.output = output;
        generated = 0;
        try {
            source(baseOmega, baseBeta, false);
            source(inverseBaseOmega, targetBaseBeta, true);
            return generated;
        } finally {
            this.output = null;
            currentSourceBeta = null;
            busy = false;
        }
    }

    private void source(final int[] omega, final int[] beta, final boolean swapped) {
        System.arraycopy(omega, 0, originalOmega, 0, sourceLength);
        for (int x = sourceLength; x < length; x++) originalOmega[x] = x;
        System.arraycopy(originalOmega, 0, parentOmega, 0, length);
        currentSourceBeta = beta;
        swappedOutput = swapped;
        gaps(0, 1);
    }

    private void gaps(final int depth, final int minimum) {
        if (depth < gain) {
            for (int slot = minimum; slot < length - (gain - depth - 1); slot++) {
                slots[depth] = slot;
                gaps(depth + 1, slot + 1);
            }
            return;
        }
        int old = 0;
        int restored = 0;
        for (int position = 0; position < length; position++) {
            final int symbol = restored < gain && slots[restored] == position
                    ? sourceLength + restored++ : currentSourceBeta[old++];
            expandedBeta[position] = symbol;
            expandedPositions[symbol] = position;
        }
        for (int position = 0; position < length; position++)
            expandedNext[expandedBeta[position]] = expandedBeta[position + 1 == length ? 0 : position + 1];
        if (swappedOutput) {
            // rho = omega * beta is unchanged by every inverse move for this
            // placement. Its cycle, anchored at symbol zero like the legacy
            // product decomposition, therefore needs construction only once.
            int x = 0;
            int visited = 0;
            for (int position = 0; position < length; position++) {
                if ((visited & (1 << x)) != 0)
                    throw new IllegalStateException("Restored target is not a full cycle");
                visited |= 1 << x;
                expandedTargetBeta[position] = x;
                x = originalOmega[expandedNext[x]];
            }
            if (x != 0) throw new IllegalStateException("Restored target did not close");
        }
        if (gain == 3) reverse(sourceLength, sourceLength + 1, sourceLength + 2);
        else if (gain == 2) {
            for (int x = 0; x < sourceLength; x++) reverse(sourceLength, sourceLength + 1, x);
        } else if (gain == 1) {
            for (int x = 0; x < sourceLength - 1; x++)
                for (int y = x + 1; y < sourceLength; y++) reverse(sourceLength, x, y);
        } else {
            for (int x = 0; x < sourceLength - 2; x++)
                for (int y = x + 1; y < sourceLength - 1; y++)
                    for (int z = y + 1; z < sourceLength; z++) reverse(x, y, z);
        }
    }

    private void reverse(int a, int b, int c) {
        // The three comparisons replace ordered()'s int[3] and Move record.
        if (expandedPositions[a] > expandedPositions[b]) { final int t = a; a = b; b = t; }
        if (expandedPositions[b] > expandedPositions[c]) { final int t = b; b = c; c = t; }
        if (expandedPositions[a] > expandedPositions[b]) { final int t = a; a = b; b = t; }

        final int oa = originalOmega[a];
        final int ob = originalOmega[b];
        final int oc = originalOmega[c];
        parentOmega[a] = oc;
        parentOmega[b] = oa;
        parentOmega[c] = ob;
        try {
            // Every restored fixed symbol is in this support, and the input
            // has no other fixed points. Only these three images can be fixed.
            if (oc == a || oa == b || ob == c) return;

            int x = expandedBeta[0];
            int visited = 0;
            for (int position = 0; position < length; position++) {
                if ((visited & (1 << x)) != 0)
                    throw new IllegalStateException("Applicable inverse did not preserve the current cycle");
                visited |= 1 << x;
                parentBeta[position] = x;
                parentPositions[x] = position;
                final int next = expandedNext[x];
                x = next == a ? b : next == b ? c : next == c ? a : next;
            }
            if (x != expandedBeta[0])
                throw new IllegalStateException("Applicable inverse current cycle did not close");

            int fa = a;
            int fb = b;
            int fc = c;
            if (parentPositions[fa] > parentPositions[fb]) { final int t = fa; fa = fb; fb = t; }
            if (parentPositions[fb] > parentPositions[fc]) { final int t = fb; fb = fc; fc = t; }
            if (parentPositions[fa] > parentPositions[fb]) { final int t = fa; fa = fb; fb = t; }
            final int forwardGain = (parentOmega[fc] == fa ? 1 : 0)
                    + (parentOmega[fa] == fb ? 1 : 0)
                    + (parentOmega[fb] == fc ? 1 : 0);
            if (forwardGain != gain) return;

            // All other images are untouched: these three equalities replay
            // omega * tau inverse over the entire permutation.
            if (parentOmega[fc] != originalOmega[fa]
                    || parentOmega[fa] != originalOmega[fb]
                    || parentOmega[fb] != originalOmega[fc])
                throw new IllegalStateException("Primitive inverse failed forward algebraic replay");
            // Replay tau * beta on every edge. Both cycles retain the old
            // anchor, so equality of successors is exact equality of cycles.
            for (int position = 0; position < length; position++) {
                final int next = parentBeta[position + 1 == length ? 0 : position + 1];
                final int replayed = next == fa ? fb : next == fb ? fc : next == fc ? fa : next;
                if (replayed != expandedNext[parentBeta[position]])
                    throw new IllegalStateException("Primitive inverse failed forward current-cycle replay");
            }
            generated++;
            if (swappedOutput) {
                for (int symbol = 0; symbol < length; symbol++) inverseParentOmega[parentOmega[symbol]] = symbol;
                output.accept(inverseParentOmega, expandedTargetBeta, length);
            } else output.accept(parentOmega, parentBeta, length);
        } finally {
            parentOmega[a] = oa;
            parentOmega[b] = ob;
            parentOmega[c] = oc;
        }
    }
}
