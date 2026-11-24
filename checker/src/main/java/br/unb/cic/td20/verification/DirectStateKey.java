package br.unb.cic.td20.verification;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitiveCanonicalizer;
import br.unb.cic.td20.model.PrimitivePair;
import lombok.val;

/**
 * Version-3 reconstruction keys. Contract every fixed point before admission.
 */
public final class DirectStateKey {

    private static final ThreadLocal<PrimitiveCanonicalizer> CANONICALIZER = ThreadLocal.withInitial(PrimitiveCanonicalizer::new);

    private DirectStateKey() {
    }

    public static byte[] encode(final PrimitivePair pair, final int maximumMoves) {
        if (!pair.isFixedPointFree()) {
            throw new IllegalArgumentException("Contract fixed points before reconstruction");
        }

        final byte[] intrinsic = pair.canonicalKey();
        val key = new byte[3 + intrinsic.length];
        prefix(key, pair.size(), maximumMoves);
        System.arraycopy(intrinsic, 0, key, 3, intrinsic.length);

        return key;
    }

    /**
     * The inverse workspace has proved the pair by actual forward replay; arrays are borrowed.
     */
    static byte[] encodeKnownPair(final int[] omega, final int[] beta, final int n, final int maximumMoves) {
        if (n < 1 || n > PrimitivePair.MAX_SYMBOLS || omega.length < n || beta.length < n) {
            throw new IllegalArgumentException("Incomplete inverse state domain");
        }
        for (int x = 0; x < n; x++) {
            if (omega[x] == x) {
                throw new IllegalArgumentException("Contract fixed points before reconstruction");
            }
        }

        final byte[] key = CANONICALIZER.get().keyForKnownPair(omega, beta, n, 3);
        prefix(key, n, maximumMoves);

        return key;
    }

    private static void prefix(final byte[] key, final int n, final int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > ErikssonRoute.ROOT_BUDGET) {
            throw new IllegalArgumentException("Budget must be 1..11");
        }

        key[0] = CheckerStateStorage.VERSION;
        key[1] = (byte) maximumMoves;
        key[2] = (byte) n;
    }

    public static byte[] encode(final CyclicTargetPair pair, final int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > ErikssonRoute.ROOT_BUDGET) {
            throw new IllegalArgumentException("Budget must be 1..11");
        }

        final int n = pair.getBeta().size();
        val key = new byte[3 + CertificateCodec.keyLength(n)];
        key[0] = CheckerStateStorage.VERSION;
        key[1] = (byte) maximumMoves;
        key[2] = (byte) n;
        CertificateCodec.writeFixedPointFreeCanonicalKey(pair, key, 3);

        return key;
    }
}
