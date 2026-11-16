package br.unb.cic.td20.verification;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.joint.ErikssonRoute;

/** Version-3 reconstruction keys. Contract every fixed point before admission. */
public final class DirectStateKey {
    private DirectStateKey() { }

    public static byte[] encode(final CyclicTargetPair pair, final int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > ErikssonRoute.ROOT_BUDGET)
            throw new IllegalArgumentException("Budget must be 1..11");
        final int n = pair.getBeta().size();
        final var key = new byte[3 + CertificateCodec.keyLength(n)];
        key[0] = CheckerStateStorage.VERSION;
        key[1] = (byte) maximumMoves;
        key[2] = (byte) n;
        CertificateCodec.writeFixedPointFreeCanonicalKey(pair, key, 3);
        return key;
    }
}
