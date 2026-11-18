package br.unb.cic.td20.experimental;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.compat.LegacyPairs;

/** Compatibility benchmark adapter for the production inverse workspace. */
public final class PrimitiveInverseReconstruction {
    @FunctionalInterface public interface RawConsumer { void accept(int[] omega, int[] beta, int length); }
    private final br.unb.cic.td20.avoidance.PrimitiveInverseReconstruction delegate =
            new br.unb.cic.td20.avoidance.PrimitiveInverseReconstruction();
    public long preimages(CyclicTargetPair child, int gain, RawConsumer output) {
        java.util.Objects.requireNonNull(output);
        return delegate.preimages(LegacyPairs.fromLegacy(child), gain, output::accept);
    }
}
