package br.unb.cic.td20.compat;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.util.Arrays;

/** Explicit adapters for the general mathematical/test API, outside production computation. */
public final class LegacyPairs {
    private LegacyPairs() { }
    public static PrimitivePair fromLegacy(CyclicTargetPair pair) {
        int n = pair.getBeta().size();
        int[] omega = new int[n];
        for (int i = 0; i < n; i++) omega[i] = pair.getOmega().image(i);
        return PrimitivePair.of(omega, pair.getBeta().getSymbols());
    }
    public static CyclicTargetPair toLegacy(PrimitivePair pair) {
        var omega = new MulticyclePermutation();
        int visited = 0;
        for (int start = 0; start < pair.size(); start++) {
            if ((visited & (1 << start)) != 0) continue;
            int[] cycle = new int[pair.size()];
            int x = start, length = 0;
            do { visited |= 1 << x; cycle[length++] = x; x = pair.omegaImage(x); } while (x != start);
            omega.add(Cycle.of(Arrays.copyOf(cycle, length)));
        }
        return CyclicTargetPair.ofOwned(omega, Cycle.of(pair.betaSymbols()));
    }
}
