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
    /**
     * Checks the complete joint-three routing with arbitrary applicable
     * supports. This is deliberately independent of the constrained emitter:
     * every qualifying edge is obtained by replay, including the
     * totally-unoriented stop at both intermediate depths.
     */
    public static boolean residual(final CyclicTargetPair pair) {
        final int[] p = images(pair), beta = pair.getBeta().getSymbols();
        if (fixed(p) != 0) throw new IllegalArgumentException("Contract fixed points first");
        if (totallyUnoriented(p, beta)) return false;
        final int n = p.length, initialOdd = odd(p);
        for (int i = 0; i < n - 2; i++) for (int j = i + 1; j < n - 1; j++)
            for (int k = j + 1; k < n; k++) {
                final int a = beta[i], b = beta[j], c = beta[k];
                final int firstGain = gain(p, a, b, c);
                final int[] q = apply(p, a, b, c);
                if (odd(q) != initialOdd + 2) continue;
                if (firstGain >= 2) return false;
                if (firstGain != 1) continue;
                final int[] nextBeta = transpose(beta, i, j, k);
                if (totallyUnoriented(q, nextBeta)) continue;
                final int nextOdd = initialOdd + 2;
                for (int r = 0; r < n - 2; r++) for (int s = r + 1; s < n - 1; s++)
                    for (int t = s + 1; t < n; t++) {
                        final int x = nextBeta[r], y = nextBeta[s], z = nextBeta[t];
                        if (q[x] == x || q[y] == y || q[z] == z) continue;
                        final int secondGain = gain(q, x, y, z);
                        if (secondGain < 1) continue;
                        final int[] successor = apply(q, x, y, z);
                        if (odd(successor) != nextOdd + 2) continue;
                        if (secondGain >= 3) return false;
                        if (secondGain > 2) continue;
                        final int[] thirdBeta = transpose(nextBeta, r, s, t);
                        if (totallyUnoriented(successor, thirdBeta)) continue;
                        final int successorOdd = nextOdd + 2;
                        for (int u = 0; u < n - 2; u++)
                            for (int v = u + 1; v < n - 1; v++)
                                for (int w = v + 1; w < n; w++) {
                                    final int d = thirdBeta[u], e = thirdBeta[v], f = thirdBeta[w];
                                    if (successor[d] == d || successor[e] == e
                                            || successor[f] == f) continue;
                                    final int thirdGain = gain(successor, d, e, f);
                                    if (secondGain == 1 && thirdGain != 3
                                            || secondGain == 2 && thirdGain < 2) continue;
                                    if (odd(apply(successor, d, e, f)) == successorOdd + 2)
                                        return false;
                                }
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
