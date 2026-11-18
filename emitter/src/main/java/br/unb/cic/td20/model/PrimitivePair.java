package br.unb.cic.td20.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable emitter state on 1..21 symbols. Successors and cyclic orders use
 * compact byte vectors; mutable computation belongs to worker-owned int arrays.
 * No disjoint-cycle objects are created, including at canonical key boundaries.
 */
public final class PrimitivePair {
    public static final int MAX_SYMBOLS = 21;
    private static final ThreadLocal<PrimitiveCanonicalizer> CANONICALIZER =
            ThreadLocal.withInitial(PrimitiveCanonicalizer::new);
    private final byte[] omega, beta, rho;
    private final int fixedMask;
    private final boolean canonical;
    private volatile byte[] canonicalKey;
    private volatile PrimitivePair representative;

    private PrimitivePair(byte[] omega, byte[] beta, byte[] rho, boolean canonical) {
        this.omega = omega; this.beta = beta; this.rho = rho; this.canonical = canonical;
        int fixed = 0;
        for (int i = 0; i < omega.length; i++) if (Byte.toUnsignedInt(omega[i]) == i) fixed |= 1 << i;
        fixedMask = fixed;
        if (canonical) representative = this;
    }

    public static PrimitivePair of(int[] omega, int[] beta) {
        Objects.requireNonNull(omega); Objects.requireNonNull(beta);
        if (omega.length != beta.length) throw new IllegalArgumentException("Pair domains disagree");
        return create(bytes(omega), bytes(beta), false);
    }

    /** Fully validates a normalized pair; canonicality is a proved caller invariant. */
    public static PrimitivePair fromNormalizedImages(byte[] omega, boolean canonical) {
        Objects.requireNonNull(omega);
        byte[] beta = new byte[omega.length];
        for (int i = 0; i < beta.length; i++) beta[i] = (byte) i;
        return create(omega.clone(), beta, canonical);
    }

    public static PrimitivePair ofCanonicalNormalized(int[] omega, int[] rho) {
        Objects.requireNonNull(rho);
        var pair = fromNormalizedImages(bytes(omega), true);
        if (rho.length != pair.size()) throw new IllegalArgumentException("Target domain disagrees");
        // A cyclic order may use any anchor, but must have the same successors.
        int seen = 0;
        for (int i = 0; i < rho.length; i++) {
            int symbol = rho[i], next = rho[(i + 1) % rho.length];
            if (symbol < 0 || symbol >= rho.length || next < 0 || next >= rho.length
                    || (seen & (1 << symbol)) != 0 || pair.omegaImage((symbol + 1) % rho.length) != next)
                throw new IllegalArgumentException("Known target disagrees with omega * beta");
            seen |= 1 << symbol;
        }
        return pair;
    }

    private static PrimitivePair create(byte[] omega, byte[] beta, boolean canonical) {
        int n = omega.length;
        if (n < 1 || n > MAX_SYMBOLS || beta.length != n)
            throw new IllegalArgumentException("Primitive pair domain must be 1..21");
        int images = 0, symbols = 0;
        int[] target = new int[n];
        for (int i = 0; i < n; i++) {
            int image = Byte.toUnsignedInt(omega[i]), symbol = Byte.toUnsignedInt(beta[i]);
            if (image >= n || symbol >= n || (images & (1 << image)) != 0 || (symbols & (1 << symbol)) != 0)
                throw new IllegalArgumentException("Pair vectors must be domain bijections");
            images |= 1 << image; symbols |= 1 << symbol;
        }
        for (int i = 0; i < n; i++) target[Byte.toUnsignedInt(beta[i])] =
                Byte.toUnsignedInt(omega[Byte.toUnsignedInt(beta[(i + 1) % n])]);
        byte[] rho = new byte[n];
        int x = 0, visited = 0;
        for (int i = 0; i < n; i++) {
            if ((visited & (1 << x)) != 0) throw new IllegalArgumentException("Target is not a full cycle");
            visited |= 1 << x; rho[i] = (byte) x; x = target[x];
        }
        if (x != 0) throw new IllegalArgumentException("Target cycle did not close");
        return new PrimitivePair(omega, beta, rho, canonical);
    }

    private static byte[] bytes(int[] values) {
        Objects.requireNonNull(values);
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] < 0 || values[i] >= MAX_SYMBOLS) throw new IllegalArgumentException("Symbol outside primitive domain");
            result[i] = (byte) values[i];
        }
        return result;
    }

    public int size() { return omega.length; }
    public int omegaImage(int symbol) { return Byte.toUnsignedInt(omega[symbol]); }
    public int betaAt(int position) { return Byte.toUnsignedInt(beta[position]); }
    public int rhoAt(int position) { return Byte.toUnsignedInt(rho[position]); }
    public int fixedMask() { return fixedMask; }
    public boolean isFixedPointFree() { return fixedMask == 0; }
    public boolean isCanonical() { return canonical; }
    public int nonfixedSymbols() { return size() - Integer.bitCount(fixedMask); }
    public byte[] omegaBytes() { return omega.clone(); }
    public byte[] betaBytes() { return beta.clone(); }
    public int[] omegaImages() { return ints(omega); }
    public int[] betaSymbols() { return ints(beta); }
    public int[] rhoSymbols() { return ints(rho); }
    public void copyOmega(int[] destination) { copy(omega, destination); }
    public void copyBeta(int[] destination) { copy(beta, destination); }
    public void copyRho(int[] destination) { copy(rho, destination); }
    private static int[] ints(byte[] values) { int[] result = new int[values.length]; copy(values, result); return result; }
    private static void copy(byte[] values, int[] destination) {
        if (destination.length < values.length) throw new IllegalArgumentException("Incomplete destination vector");
        for (int i = 0; i < values.length; i++) destination[i] = Byte.toUnsignedInt(values[i]);
    }

    /** Existing five-bit intrinsic format, with independent owned bytes per caller. */
    public byte[] canonicalKey() {
        byte[] result = canonicalKey;
        if (result == null) {
            result = canonical ? normalizedKey() : CANONICALIZER.get().keyFor(omegaImages(), betaSymbols(), size());
            canonicalKey = result;
        }
        return result.clone();
    }

    public byte[] normalizedKey() {
        for (int i = 0; i < size(); i++) if (betaAt(i) != i)
            throw new IllegalArgumentException("Certificate keys require normalized beta");
        byte[] key = new byte[(5 * size() + 7) / 8];
        for (int i = 0; i < size(); i++) {
            int bit = 5 * i, index = bit >>> 3, remainder = bit & 7, image = omegaImage(i);
            if (remainder <= 3) key[index] |= (byte) (image << (3 - remainder));
            else { key[index] |= (byte) (image >>> (remainder - 3)); key[index + 1] |= (byte) (image << (11 - remainder)); }
        }
        return key;
    }

    public static PrimitivePair fromCanonicalKey(byte[] key, int n) {
        Objects.requireNonNull(key);
        if (n < 1 || n > MAX_SYMBOLS || key.length != (5 * n + 7) / 8)
            throw new IllegalArgumentException("Malformed primitive certificate key");
        int padding = key.length * 8 - 5 * n;
        if (padding != 0 && (key[key.length - 1] & ((1 << padding) - 1)) != 0)
            throw new IllegalArgumentException("Nonzero certificate key padding");
        byte[] omega = new byte[n];
        for (int i = 0; i < n; i++) {
            int value = 0;
            for (int offset = 0; offset < 5; offset++) {
                int bit = 5 * i + offset;
                value = (value << 1) | ((key[bit >>> 3] >>> (7 - (bit & 7))) & 1);
            }
            omega[i] = (byte) value;
        }
        var result = fromNormalizedImages(omega, true);
        result.canonicalKey = key.clone();
        return result;
    }

    public PrimitivePair canonicalRepresentative() {
        PrimitivePair result = representative;
        if (result == null) { result = fromCanonicalKey(canonicalKey(), size()); representative = result; }
        return result;
    }

    public PrimitivePair swapped() {
        int[] inverse = new int[size()];
        for (int i = 0; i < size(); i++) inverse[omegaImage(i)] = i;
        return of(inverse, rhoSymbols());
    }

    public int threeNorm() {
        int visited = 0, odd = 0;
        for (int start = 0; start < size(); start++) {
            if ((visited & (1 << start)) != 0) continue;
            int x = start, length = 0;
            do { visited |= 1 << x; length++; x = omegaImage(x); } while (x != start);
            odd += length & 1;
        }
        return (size() - odd) / 2;
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof PrimitivePair pair) || !Arrays.equals(omega, pair.omega)) return false;
        int first = zeroPosition(), second = pair.zeroPosition();
        for (int i = 0; i < size(); i++)
            if (betaAt((first + i) % size()) != pair.betaAt((second + i) % size())) return false;
        return true;
    }
    private int zeroPosition() {
        int position = 0;
        while (beta[position] != 0) position++;
        return position;
    }
    @Override public int hashCode() {
        int cycleHash = 1, first = zeroPosition();
        for (int i = 0; i < size(); i++) cycleHash = 31 * cycleHash + betaAt((first + i) % size());
        return 31 * Arrays.hashCode(omega) + cycleHash;
    }
    @Override public String toString() { return "PrimitivePair(" + size() + ",omega=" + Arrays.toString(omega) + ")"; }
}
