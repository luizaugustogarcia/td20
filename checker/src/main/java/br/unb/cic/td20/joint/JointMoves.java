package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import java.util.Arrays;

/** Independent image-array replay and exhaustive support tests. */
public final class JointMoves {
    private JointMoves() { }
    public static int[] images(final CyclicTargetPair pair) {
        final int[] p = new int[pair.getBeta().size()];
        for (int i = 0; i < p.length; i++) p[i] = pair.getOmega().image(i);
        return p;
    }
    public static int fixed(final int[] p) {
        int result = 0;
        for (int i = 0; i < p.length; i++) if (p[i] == i) result++;
        return result;
    }
    public static int odd(final int[] p) {
        final boolean[] seen = new boolean[p.length];
        int odd = 0;
        for (int i = 0; i < p.length; i++) {
            if (seen[i]) continue;
            int x = i, length = 0;
            do { seen[x] = true; length++; x = p[x]; } while (x != i);
            odd += length % 2;
        }
        return odd;
    }
    public static int[] apply(final int[] p, final int a, final int b, final int c) {
        final int[] q = p.clone();
        q[a] = p[c]; q[b] = p[a]; q[c] = p[b];
        return q;
    }
    public static int gain(final int[] p, final int a, final int b, final int c) {
        return (p[a] == b ? 1 : 0) + (p[b] == c ? 1 : 0) + (p[c] == a ? 1 : 0);
    }
    public static boolean totallyUnoriented(final int[] p, final int[] beta) {
        final int[] position = new int[p.length];
        for (int i = 0; i < beta.length; i++) position[beta[i]] = i;
        final boolean[] seen = new boolean[p.length];
        for (int i = 0; i < p.length; i++) {
            if (seen[i]) continue;
            int x = i, increases = 0, length = 0;
            do {
                seen[x] = true; length++;
                if (position[p[x]] > position[x]) increases++;
                x = p[x];
            } while (x != i);
            if (length >= 3 && increases != 1) return false;
        }
        return true;
    }
    /** Checks every first and second triple; no consecutive-edge shortcut. */
    public static boolean residual(final CyclicTargetPair pair) {
        final int[] p = images(pair), beta = pair.getBeta().getSymbols();
        if (fixed(p) != 0) throw new IllegalArgumentException("Contract fixed points first");
        if (totallyUnoriented(p, beta)) return false;
        final int n = p.length, initialOdd = odd(p);
        for (int i = 0; i < n - 2; i++) for (int j = i + 1; j < n - 1; j++)
            for (int k = j + 1; k < n; k++) {
                final int a = beta[i], b = beta[j], c = beta[k];
                if (gain(p, a, b, c) >= 2) return false;
                if (gain(p, a, b, c) != 1) continue;
                final int[] q = apply(p, a, b, c);
                if (odd(q) != initialOdd + 2) continue;
                final int[] nextBeta = transpose(beta, i, j, k);
                if (totallyUnoriented(q, nextBeta)) continue;
                for (int r = 0; r < n - 2; r++) for (int s = r + 1; s < n - 1; s++)
                    for (int t = s + 1; t < n; t++) {
                        final int x = nextBeta[r], y = nextBeta[s], z = nextBeta[t];
                        if (q[x] != x && q[y] != y && q[z] != z && gain(q, x, y, z) >= 2)
                            return false;
                    }
            }
        return true;
    }
    public static int[] transpose(final int[] beta, final int i, final int j, final int k) {
        final int[] result = beta.clone();
        System.arraycopy(beta, j, result, i, k - j);
        System.arraycopy(beta, i, result, i + k - j, j - i);
        return result;
    }
    public static int[] identity(final int n) {
        final int[] p = new int[n]; Arrays.setAll(p, i -> i); return p;
    }
}
