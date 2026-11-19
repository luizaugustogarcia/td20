package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.PrimitivePair;

/** Worker-owned, exactly replayed one-move diameter closure on either endpoint. */
public final class ImmediateRootClosure {
    private final int[] positions = new int[21], inverse = new int[21];
    private final int[] targetNext = new int[21], target = new int[21], movedNext = new int[21];

    /** Input arrays represent an already validated pair with all fixed points contracted. */
    public boolean closes(int[] omega, int[] beta, int n) {
        if (n < 3 || n > 21 || omega.length < n || beta.length < n)
            throw new IllegalArgumentException("Expected a complete pair on 3..21 symbols");
        for (int x = 0; x < n; x++) {
            if (omega[x] == x) throw new IllegalArgumentException("Contract fixed points before root closure");
            positions[beta[x]] = x;
        }
        if (oneSide(omega, beta, n)) return true;
        for (int i = 0; i < n; i++) {
            inverse[omega[i]] = i;
            targetNext[beta[i]] = omega[beta[i + 1 == n ? 0 : i + 1]];
        }
        int x = 0;
        for (int i = 0; i < n; i++) { target[i] = x; positions[x] = i; x = targetNext[x]; }
        return oneSide(inverse, target, n);
    }

    public boolean closes(PrimitivePair pair) {
        return closes(pair.omegaImages(), pair.betaSymbols(), pair.size());
    }

    private boolean oneSide(int[] omega, int[] beta, int n) {
        for (int a = 0; a < n; a++) {
            int b = omega[a], c = omega[b];
            if (a == b || a == c || b == c) continue;
            int pa = positions[a], pb = positions[b], pc = positions[c];
            if (!((pa < pb && pb < pc) || (pb < pc && pc < pa) || (pc < pa && pa < pb))) continue;
            // Replay omega * tau^-1, all current-cycle edges, unchanged rho,
            // actual fixed-point gain and the remaining known diameter.
            int fixed = 0;
            for (int symbol = 0; symbol < n; symbol++)
                if (image(omega, symbol, a, b, c) == symbol) fixed++;
            if (fixed < 2) throw new IllegalStateException("Consecutive support failed actual gain replay");
            for (int i = 0; i < n; i++) {
                int next = beta[i + 1 == n ? 0 : i + 1];
                int moved = next == a ? b : next == b ? c : next == c ? a : next;
                movedNext[beta[i]] = moved;
                if (image(omega, moved, a, b, c) != omega[next])
                    throw new IllegalStateException("Root closure changed its search target");
            }
            int visited = 0, x = beta[0];
            for (int i = 0; i < n; i++) {
                if ((visited & (1 << x)) != 0) throw new IllegalStateException("Root closure split its current cycle");
                visited |= 1 << x; x = movedNext[x];
            }
            if (x != beta[0]) throw new IllegalStateException("Root closure current cycle did not close");
            if (KnownDiameters.knownDiameter(n - fixed) > 10)
                throw new IllegalStateException("Root closure exceeded budget eleven");
            return true;
        }
        return false;
    }

    private static int image(int[] omega, int x, int a, int b, int c) {
        return x == a ? omega[c] : x == b ? omega[a] : x == c ? omega[b] : omega[x];
    }
}
