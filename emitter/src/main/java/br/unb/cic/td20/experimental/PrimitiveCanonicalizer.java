package br.unb.cic.td20.experimental;

/**
 * Benchmark adapter; production owns the single canonicalization implementation.
 */
public final class PrimitiveCanonicalizer {

    public static final int MAX_SYMBOLS = br.unb.cic.td20.model.PrimitiveCanonicalizer.MAX_SYMBOLS;
    private final br.unb.cic.td20.model.PrimitiveCanonicalizer delegate = new br.unb.cic.td20.model.PrimitiveCanonicalizer();

    public byte[] keyFor(final int[] omega, final int[] beta, final int n) {
        return delegate.keyFor(omega, beta, n);
    }

    byte[] keyForKnownPair(final int[] omega, final int[] beta, final int n) {
        return delegate.keyForKnownPair(omega, beta, n);
    }
}
