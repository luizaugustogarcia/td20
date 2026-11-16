package br.unb.cic.td20.verification;

import br.unb.cic.td20.joint.CyclicTargetMoves;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import java.util.Objects;

/** A contracted obligation with its explicit sorting budget. */
record SearchState(int maximumMoves, CyclicTargetPair pair) {
    SearchState {
        Objects.requireNonNull(pair);
        if (maximumMoves<1 || maximumMoves>KnownDiameters.MAX_BUDGET)
            throw new IllegalArgumentException("Invalid reconstruction budget");
        if (CyclicTargetMoves.fixed(CyclicTargetMoves.images(pair))!=0)
            throw new IllegalArgumentException("Reconstruction states must be contracted");
    }
}
