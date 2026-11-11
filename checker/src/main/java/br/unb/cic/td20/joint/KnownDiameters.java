package br.unb.cic.td20.joint;

/** Established TD(N-1) bounds for a cyclic domain of N symbols. */
public final class KnownDiameters {
    public static final int ROOT_SYMBOLS = 21;
    public static final int MAX_BUDGET = 11;
    /** Indexed by cyclic-target domain N and equal to TD(N-1). */
    private static final int[] BOUNDS = {0, 0, 0, 1, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 8, 8, 9, 9, 10, 10, 11};
    private KnownDiameters() { }
    public static int knownDiameter(final int domain) {
        if (domain < 0) throw new IllegalArgumentException("Negative domain");
        return domain < BOUNDS.length ? BOUNDS[domain] : Integer.MAX_VALUE;
    }
    public static boolean closes(final int domain, final int movesUsed) {
        if (movesUsed < 0) throw new IllegalArgumentException("Negative move count");
        return movesUsed <= MAX_BUDGET && knownDiameter(domain) <= MAX_BUDGET - movesUsed;
    }
    public static int threeNorm(final int[] lengths) {
        return java.util.Arrays.stream(lengths).map(n -> n / 2).sum();
    }
}
