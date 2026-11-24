package br.unb.cic.td20.verification;

import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;

import java.util.Objects;

/**
 * A contracted obligation with its explicit sorting budget.
 */
record SearchState(int maximumMoves, PrimitivePair pair) {

    SearchState(final int maximumMoves, final CyclicTargetPair pair) {
        this(maximumMoves, LegacyPairs.fromLegacy(pair));
    }

    SearchState {
        Objects.requireNonNull(pair);
        if (maximumMoves < 1 || maximumMoves > KnownDiameters.MAX_BUDGET) {
            throw new IllegalArgumentException("Invalid reconstruction budget");
        }
        if (!pair.isFixedPointFree()) {
            throw new IllegalArgumentException("Reconstruction states must be contracted");
        }
    }
}
