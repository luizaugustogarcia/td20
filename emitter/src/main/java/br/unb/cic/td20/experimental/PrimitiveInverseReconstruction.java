package br.unb.cic.td20.experimental;

import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.model.CyclicTargetPair;

/**
 * Compatibility benchmark adapter for the production inverse workspace.
 */
public final class PrimitiveInverseReconstruction {

    @FunctionalInterface
    public interface RawConsumer {

        void accept(final int[] omega, final int[] beta, final int length);
    }

    private final br.unb.cic.td20.avoidance.PrimitiveInverseReconstruction delegate = new br.unb.cic.td20.avoidance.PrimitiveInverseReconstruction();

    public long preimages(final CyclicTargetPair child, final int gain, final RawConsumer output) {
        java.util.Objects.requireNonNull(output);
        return delegate.preimages(LegacyPairs.fromLegacy(child), gain, output::accept);
    }
}
