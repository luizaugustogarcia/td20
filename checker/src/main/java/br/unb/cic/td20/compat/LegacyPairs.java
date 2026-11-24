package br.unb.cic.td20.compat;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;

import java.util.Arrays;

/**
 * Explicit adapters for the general mathematical/test API, outside production computation.
 */
public final class LegacyPairs {

    private LegacyPairs() {
    }

    public static PrimitivePair fromLegacy(final CyclicTargetPair pair) {
        final int n = pair.getBeta().size();
        val omega = new int[n];
        for (int i = 0; i < n; i++) {
            omega[i] = pair.getOmega().image(i);
        }

        val result = PrimitivePair.of(omega, pair.getBeta().getSymbols());
        // The legacy known-target factory is unchecked. Preserve the checker's
        // target obligation instead of silently replacing a supplied target.
        val rho = pair.getRho();
        if (rho.size() != n) {
            throw new IllegalArgumentException("Legacy target domain disagrees");
        }
        for (int i = 0; i < n; i++) {
            final int symbol = result.rhoAt(i);
            if (rho.image(symbol) != result.rhoAt((i + 1) % n)) {
                throw new IllegalArgumentException("Legacy target does not equal omega * beta");
            }
        }

        return result;
    }

    public static CyclicTargetPair toLegacy(final PrimitivePair pair) {
        val omega = new MulticyclePermutation();
        int visited = 0;
        for (int start = 0; start < pair.size(); start++) {
            if ((visited & (1 << start)) != 0) {
                continue;
            }

            val cycle = new int[pair.size()];
            int x = start, length = 0;
            do {
                visited |= 1 << x;
                cycle[length++] = x;
                x = pair.omegaImage(x);
            } while (x != start);

            omega.add(Cycle.of(Arrays.copyOf(cycle, length)));
        }

        return CyclicTargetPair.ofOwned(omega, Cycle.of(pair.betaSymbols()));
    }
}
