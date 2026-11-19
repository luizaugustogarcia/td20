package br.unb.cic.td20.experimental;

import br.unb.cic.td20.model.PrimitivePair;
import java.util.List;

/**
 * Experimental batched CUDA inverse generation and canonicalization.
 * Never used by the emitter or checker. Each support has a deterministic
 * output slot: zero means rejected; otherwise it contains a version-three
 * state key. The full worst-case allocation prevents output truncation.
 */
public final class GpuInverseReconstruction {
    private static final int KEY_BYTES = 17;
    private static final int MAX_OUTPUT_BYTES = 256 << 20;
    private static final class Library {
        static { System.loadLibrary("td20_inverse_benchmark"); }
        static void load() { }
    }

    private GpuInverseReconstruction() { }

    public static int candidatesPerSource(int n, int gain) {
        if (n < 3 || n > 21 || gain < 0 || gain > 3 || gain > 21 - n)
            throw new IllegalArgumentException("Invalid experimental inverse domain or gain");
        int placements = choose(n + gain - 1, gain);
        int supports = choose(n, 3 - gain);
        return Math.multiplyExact(2, Math.multiplyExact(placements, supports));
    }

    private static int choose(int n, int k) {
        int result = 1;
        for (int i = 1; i <= k; i++) result = result * (n - i + 1) / i;
        return result;
    }

    public static byte[] expand(List<PrimitivePair> children, int gain, int maximumMoves) {
        if (children.isEmpty()) throw new IllegalArgumentException("Empty GPU inverse batch");
        int n = children.getFirst().size();
        int candidates = candidatesPerSource(n, gain);
        int bytes = Math.multiplyExact(KEY_BYTES, Math.multiplyExact(children.size(), candidates));
        if (bytes > MAX_OUTPUT_BYTES) throw new IllegalArgumentException("GPU inverse batch exceeds 256 MiB output limit");
        if (maximumMoves < 1 || maximumMoves > 11) throw new IllegalArgumentException("Invalid obligation budget");
        byte[] packed = new byte[Math.multiplyExact(children.size(), 3 * n)];
        int offset = 0;
        for (var child : children) {
            if (child.size() != n || !child.isFixedPointFree())
                throw new IllegalArgumentException("GPU inverse inputs require a common contracted domain");
            for (int i = 0; i < n; i++) packed[offset++] = (byte) child.omegaImage(i);
            for (int i = 0; i < n; i++) packed[offset++] = (byte) child.betaAt(i);
            for (int i = 0; i < n; i++) packed[offset++] = (byte) child.rhoAt(i);
        }
        Library.load();
        byte[] output = expandNative(packed, n, gain, maximumMoves, bytes);
        if (output == null || output.length != bytes) throw new IllegalStateException("Incomplete GPU inverse result");
        return output;
    }

    private static native byte[] expandNative(byte[] packed, int n, int gain, int maximumMoves, int outputBytes);
}
