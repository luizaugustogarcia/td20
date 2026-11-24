package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import lombok.val;

import java.util.Arrays;

/**
 * Independent image-array replay and exhaustive support tests.
 */
public final class CyclicTargetMoves {

    private CyclicTargetMoves() {
    }

    public static int[] images(final CyclicTargetPair pair) {
        val p = new int[pair.getBeta().size()];
        for (int i = 0; i < p.length; i++) {
            p[i] = pair.getOmega().image(i);
        }

        return p;
    }

    public static int fixed(final int[] p) {
        int result = 0;
        for (int i = 0; i < p.length; i++) {
            if (p[i] == i) {
                result++;
            }
        }

        return result;
    }

    public static int[] apply(final int[] p, final int a, final int b, final int c) {
        final int[] q = p.clone();
        q[a] = p[c];
        q[b] = p[a];
        q[c] = p[b];

        return q;
    }

    public static int gain(final int[] p, final int a, final int b, final int c) {
        return (p[a] == b ? 1 : 0) + (p[b] == c ? 1 : 0) + (p[c] == a ? 1 : 0);
    }

    public static boolean totallyUnoriented(final int[] p, final int[] beta) {
        val position = new int[p.length];
        for (int i = 0; i < beta.length; i++) {
            position[beta[i]] = i;
        }

        val seen = new boolean[p.length];
        for (int i = 0; i < p.length; i++) {
            if (seen[i]) {
                continue;
            }

            int x = i, increases = 0, length = 0;
            do {
                seen[x] = true;
                length++;
                if (position[p[x]] > position[x]) {
                    increases++;
                }

                x = p[x];
            } while (x != i);
            if (length >= 3 && increases != 1) {
                return false;
            }
        }

        return true;
    }

    public static int[] identity(final int n) {
        val p = new int[n];
        Arrays.setAll(p, i -> i);

        return p;
    }
}
