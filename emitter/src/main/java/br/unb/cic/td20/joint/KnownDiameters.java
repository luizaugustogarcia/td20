package br.unb.cic.td20.joint;

/** Established transposition-diameter upper bounds, indexed by cyclic-target domain size. */
public final class KnownDiameters {
    public static final int ROOT_SYMBOLS = 21;
    public static final int MAX_BUDGET = 11;
    /** TD(18)=10 is the explicit completed-premise assumption for this project. */
    private static final int[] TD = {0, 0, 1, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 8, 8, 9, 9, 10, 10, 11};

    private KnownDiameters() { }

    /** Returns an established upper bound for TD(N-1), or Integer.MAX_VALUE when none is assumed. */
    public static int knownDiameter(final int cyclicDomain) {
        if (cyclicDomain < 0) throw new IllegalArgumentException(
                "The cyclic domain cannot be negative");
        if (cyclicDomain <= 1) return 0;
        return cyclicDomain < ROOT_SYMBOLS ? TD[cyclicDomain - 1] : Integer.MAX_VALUE;
    }

    public static boolean closes(final int cyclicDomain, final int movesUsed) {
        if (movesUsed < 0) throw new IllegalArgumentException(
                "The number of spent moves cannot be negative");
        final int diameter = knownDiameter(cyclicDomain);
        return movesUsed <= MAX_BUDGET && diameter <= MAX_BUDGET - movesUsed;
    }

    /** The 3-norm is the sum of floor(length/2), including zero for fixed points. */
    public static int threeNorm(final int[] partition) {
        int result = 0;
        for (final int length : partition) {
            if (length < 1) throw new IllegalArgumentException(
                    "Algebraic cycle lengths must be positive");
            result = Math.addExact(result, length / 2);
        }
        return result;
    }
}
